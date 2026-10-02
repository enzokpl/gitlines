package dev.gitlines.git;

import dev.gitlines.model.RepositoryInfo;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Avoids Git inflating very large binary blobs merely to classify numstat data.
 * Classification uses Git's 8000-byte NUL rule, never extensions. Every changed
 * version of an eligible path must pass; text, explicit attributes and uncertain
 * probes retain ordinary Git behavior. Collections have fixed bounds independent
 * of history length; all discovery streams are consumed incrementally. Discovery
 * visits metadata in pack locality order rather than sorting object IDs, avoiding
 * repeated pack-window remapping. It selects blobs of at least 32 MiB, at most eight objects, sixteen exact ASCII
 * paths and 128 versions. Its two-second total and 750-ms process budgets bound
 * delays; Git may still allocate decoded delta objects before a timeout.
 */
final class BinaryPreflight implements AutoCloseable {
    private static final long MINIMUM_SIZE = 32L * 1024 * 1024;
    private final RepositoryInfo repository;
    private final long deadline = System.nanoTime() + 2_000_000_000L;
    private Path attributes;
    private Thread attributeCleanup;
    private final byte[] tokenBuffer = new byte[8192];

    /**
     * Pins optional discovery to the same revision as the real analysis.
     * @param repository immutable analysis metadata
     */
    BinaryPreflight(RepositoryInfo repository) {
        this.repository = repository;
    }

    /**
     * Builds subprocess-only attributes after complete validation, or returns no
     * override. Existing global attributes are conservatively preserved by skipping
     * optimization. Failure never prevents the ordinary authoritative log command.
     * @return optional Git configuration arguments
     */
    List<String> configuration() {
        try {
            if (hasGlobalAttributes()) {
                return List.of();
            }
            var candidates = new HashSet<String>();
            var inventory = List.of(
                "cat-file",
                "--batch-all-objects",
                "--unordered",
                "--batch-check=%(objectname) %(objecttype) %(objectsize)"
            );
            try (var git = probe(inventory);
                var input = new BufferedInputStream(git.process().getInputStream())) {
                String line;
                while ((line = token(input, '\n')) != null) {
                    int first = line.indexOf(' ');
                    int second = line.indexOf(' ', first + 1);
                    if (first < 0 || second < 0) {
                        throw new IOException("invalid object metadata");
                    }
                    if (line.substring(first + 1, second).equals("blob")
                        && Long.parseLong(line.substring(second + 1)) >= MINIMUM_SIZE) {
                        if (candidates.size() == 8) {
                            return List.of();
                        }
                        candidates.add(line.substring(0, first));
                    }
                }
                requireSuccess(git);
            }
            if (candidates.isEmpty()) {
                return List.of();
            }
            var paths = new HashSet<String>();
            scan(candidates, paths, false, new HashSet<>());
            var checked = new HashSet<String>();
            scan(candidates, paths, true, checked);
            var rules = new StringBuilder();
            for (var path : paths) {
                if (unspecifiedDiff(path)) {
                    rules.append('/').append(path).append(" -diff\n");
                }
            }
            if (rules.isEmpty()) {
                return List.of();
            }
            attributes = Files.createTempFile("gitlines-binary-", ".attributes");
            Path temporary = attributes;
            attributeCleanup = new Thread(() -> {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException unavailable) {
                    // Normal resource closure also attempts cleanup.
                }
            }, "gitlines-attributes-cleanup");
            Runtime.getRuntime().addShutdownHook(attributeCleanup);
            Files.writeString(attributes, rules);
            return List.of("-c", "core.attributesFile=" + attributes);
        } catch (IOException | NumberFormatException inconclusive) {
            return List.of();
        }
    }

    /**
     * Preserves configured and default global attribute files without copying or
     * interpreting their macros, patterns or precedence.
     * @return whether overriding the global file could discard existing rules
     * @throws IOException if discovery fails
     */
    private boolean hasGlobalAttributes() throws IOException {
        try (var git = probe(List.of("config", "--get", "core.attributesFile"));
            var input = git.process().getInputStream()) {
            boolean configured = input.read() != -1;
            int status = git.await();
            if (status != 0 && status != 1) {
                throw new IOException("attribute configuration unavailable");
            }
            String xdg = System.getenv("XDG_CONFIG_HOME");
            String home = System.getenv("HOME");
            return configured || (xdg != null && Files.exists(Path.of(xdg, "git", "attributes")))
                || (home != null && Files.exists(Path.of(home, ".config", "git", "attributes")));
        }
    }

    /**
     * Reads NUL-separated native raw diffs twice: first locating candidate paths,
     * then checking every old/new version, including versions seen before discovery.
     * Renames are expanded into delete/add so both paths receive full validation.
     * @param candidates bounded large-object IDs
     * @param paths bounded eligible paths, removed on any uncertain version
     * @param validate whether this is the validation pass
     * @param checked bounded successful binary probe cache
     * @throws IOException if discovery is incomplete or exceeds its bounds
     */
    private void scan(
        Set<String> candidates,
        Set<String> paths,
        boolean validate,
        Set<String> checked
    ) throws IOException {
        var arguments = List.of(
            "log",
            "--no-merges",
            "--root",
            "--no-renames",
            "--no-ext-diff",
            "--no-textconv",
            "--raw",
            "-z",
            "--no-abbrev",
            "--format=",
            repository.revision(),
            "--"
        );
        try (var git = probe(arguments);
            var input = new BufferedInputStream(git.process().getInputStream())) {
            String header;
            while ((header = token(input, 0)) != null) {
                header = header.stripLeading();
                if (header.isEmpty()) {
                    continue;
                }
                if (!header.startsWith(":")) {
                    throw new IOException("invalid raw diff");
                }
                var fields = new String[5];
                int offset = 1;
                for (int index = 0; index < fields.length; index++) {
                    int end = index == fields.length - 1 ? header.length() : header.indexOf(' ', offset);
                    if (end < offset) {
                        throw new IOException("invalid raw fields");
                    }
                    fields[index] = header.substring(offset, end);
                    offset = end + 1;
                }
                String path = token(input, 0);
                if (path == null) {
                    throw new IOException("incomplete raw diff");
                }
                if (!validate && (candidates.contains(fields[2]) || candidates.contains(fields[3]))) {
                    if (paths.size() == 16) {
                        throw new IOException("too many candidate paths");
                    }
                    if (path.matches("[A-Za-z0-9_./-]+")) {
                        paths.add(path);
                    }
                }
                if (validate && paths.contains(path)) {
                    for (int side = 0; side < 2; side++) {
                        String mode = fields[side];
                        String object = fields[side + 2];
                        if (mode.equals("000000")) {
                            continue;
                        }
                        if (!mode.startsWith("100") || (!checked.contains(object) && !binary(object))) {
                            paths.remove(path);
                            break;
                        }
                        if (checked.size() == 128 && !checked.contains(object)) {
                            throw new IOException("too many binary versions");
                        }
                        checked.add(object);
                    }
                }
            }
            requireSuccess(git);
        }
    }

    /**
     * Requests only the classification prefix. Lowering the helper's threshold
     * enables Git streaming for large non-delta packed objects; the actual diff
     * threshold remains unchanged so legitimate large text still counts normally.
     * @param object native Git object ID
     * @return whether a complete classification prefix contains NUL
     * @throws IOException if the probe cannot finish within its budget
     */
    private boolean binary(String object) throws IOException {
        try (var git = probe(List.of("-c", "core.bigFileThreshold=1m", "cat-file", "blob", object));
            var input = git.process().getInputStream()) {
            byte[] prefix = input.readNBytes(8000);
            if (prefix.length < 8000) {
                requireSuccess(git);
            }
            for (byte value : prefix) {
                if (value == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Defers to any explicit native diff attribute, including custom drivers.
     * @param path safe exact repository-relative path
     * @return whether Git reports no diff attribute for this path
     * @throws IOException if attribute resolution fails
     */
    private boolean unspecifiedDiff(String path) throws IOException {
        try (var git = probe(List.of("check-attr", "-z", "diff", "--", path));
            var input = git.process().getInputStream()) {
            String returned = token(input, 0);
            String attribute = token(input, 0);
            String value = token(input, 0);
            requireSuccess(git);
            return path.equals(returned) && "diff".equals(attribute) && "unspecified".equals(value);
        }
    }

    /**
     * Bounds each optional subprocess and the whole preparation; delta or slow
     * storage cases fall back instead of delaying analysis without a known benefit.
     * @param arguments native Git command arguments
     * @return owned timed process
     * @throws IOException if the preparation budget expires
     */
    private GitProcess probe(List<String> arguments) throws IOException {
        long remaining = (deadline - System.nanoTime()) / 1_000_000;
        if (remaining <= 0) {
            throw new IOException("binary preparation budget exhausted");
        }
        var bounded = new ArrayList<>(List.of(
            "-c",
            "core.packedGitWindowSize=8m",
            "-c",
            "core.packedGitLimit=32m",
            "-c",
            "core.deltaBaseCacheLimit=32m"
        ));
        bounded.addAll(arguments);
        var git = new GitProcess(repository.path(), bounded, false);
        git.limitLifetime(Math.min(remaining, 750));
        return git;
    }

    /**
     * Rejects partial discovery output after a failure or timeout.
     * @param git owned subprocess
     * @throws IOException if Git failed
     */
    private static void requireSuccess(GitProcess git) throws IOException {
        if (git.await() != 0) {
            throw new IOException("incomplete binary preparation");
        }
    }

    /**
     * Reads a bounded machine token without accumulating history or arbitrary
     * filenames. Unsupported long tokens disable only the optional optimization.
     * @param input Git stdout
     * @param delimiter native protocol delimiter
     * @return token or null at clean EOF
     * @throws IOException if a token is incomplete or exceeds 8192 bytes
     */
    private String token(InputStream input, int delimiter) throws IOException {
        byte[] bytes = tokenBuffer;
        int size = 0;
        int value;
        while ((value = input.read()) != -1) {
            if (value == delimiter) {
                return new String(bytes, 0, size, StandardCharsets.UTF_8);
            }
            if (size == bytes.length) {
                throw new IOException("oversized discovery token");
            }
            bytes[size++] = (byte) value;
        }
        if (size != 0) {
            throw new IOException("incomplete discovery token");
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        if (attributes != null) {
            try {
                Files.deleteIfExists(attributes);
            } finally {
                if (attributeCleanup != null) {
                    try {
                        Runtime.getRuntime().removeShutdownHook(attributeCleanup);
                    } catch (IllegalStateException shutdownInProgress) {
                        // Shutdown owns the remaining cleanup attempt.
                    }
                }
            }
        }
    }
}
