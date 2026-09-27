package dev.gitlines.git;

import dev.gitlines.model.RepositoryInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Uses Git to resolve worktree roots, HEAD, symbolic branches and shallow status.
 */
public final class RepositoryInfoReader {
    /**
     * Inspects a root or subdirectory without modifying its repository.
     * @param directory requested repository directory
     * @return metadata for the pinned HEAD, including an unborn HEAD
     * @throws IOException if Git is absent or the directory is not a worktree
     */
    public RepositoryInfo read(Path directory) throws IOException {
        var absolute = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            throw new IOException("'" + absolute + "' is not a directory");
        }
        String root;
        try {
            root = query(absolute, List.of("rev-parse", "--show-toplevel"), false);
        } catch (IOException error) {
            throw new IOException("git executable could not be started: " + error.getMessage(), error);
        }
        if (root == null) {
            throw new IOException("'" + absolute + "' is not inside a Git worktree");
        }
        var path = Path.of(root).toRealPath();
        var branch = query(path, List.of("symbolic-ref", "--quiet", "--short", "HEAD"), false);
        var revision = query(path, List.of("rev-parse", "--verify", "HEAD^{commit}"), false);
        if (revision == null) {
            // Only a symbolic HEAD whose branch does not exist is a valid empty repository.
            var reference = query(path, List.of("symbolic-ref", "--quiet", "HEAD"), false);
            if (reference == null || query(path, List.of("show-ref", "--verify", reference), false) != null) {
                throw new IOException("cannot resolve repository HEAD");
            }
        }
        var shallow = query(path, List.of("rev-parse", "--is-shallow-repository"), true);
        var filename = path.getFileName();
        return new RepositoryInfo(filename == null ? path.toString() : filename.toString(), path,
            revision, branch, Boolean.parseBoolean(shallow));
    }

    /**
     * Reads small bounded metadata, preserving whitespace inside names and paths.
     * @param directory Git worktree directory
     * @param arguments Git arguments
     * @param required whether a failed command is an error rather than absent metadata
     * @return UTF-8 response without its final newline, or null on optional failure
     * @throws IOException if output exceeds the metadata bound or a required command fails
     */
    private String query(Path directory, List<String> arguments, boolean required) throws IOException {
        try (var git = new GitProcess(directory, arguments, false);
            var input = git.process().getInputStream()) {
            var bytes = input.readNBytes(65537);
            if (bytes.length > 65536) {
                throw new IOException("Git metadata exceeds 64 KiB");
            }
            int status = git.await();
            if (status != 0) {
                if (required) {
                    throw new IOException("failed to inspect repository: git exited with status " + status);
                }
                return null;
            }
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\n') {
                length--;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }
    }
}
