package dev.gitlines.git;

import dev.gitlines.model.AuthorStats;
import dev.gitlines.model.RepositoryInfo;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Streams parallel native diffs using a fixed number of persistent Git
 * processes. Commit IDs are dispatched round-robin through bounded pipe buffers;
 * history is never collected. Each worker owns its pack and delta caches, so total
 * Git memory can grow with the worker count. Output readers run concurrently to
 * prevent stdin/stdout pipe deadlocks, while author callbacks remain serialized.
 */
final class ParallelDiffs {
    /**
     * Traverses the pinned revision once and distributes only non-merge commits.
     * Any worker failure kills its peers, unblocking dispatch and preventing a
     * partial report. Shared temporary binary attributes outlive all workers.
     * @param repository immutable root and revision
     * @param configuration validated subprocess-only binary attributes
     * @param count fixed worker count
     * @param consumer serialized per-commit recipient
     * @throws IOException if dispatch, parsing, Git or interruption fails
     */
    void contributions(
        RepositoryInfo repository,
        List<String> configuration,
        int count,
        Consumer<AuthorStats> consumer
    ) throws IOException {
        var processes = new ArrayList<GitProcess>();
        var outputs = new ArrayList<BufferedOutputStream>();
        var readers = Executors.newFixedThreadPool(count);
        var futures = new ArrayList<Future<?>>();
        var failure = new AtomicReference<IOException>();
        try {
            for (int index = 0; index < count; index++) {
                var git = new GitProcess(repository.path(), diffArguments(configuration), true);
                processes.add(git);
                outputs.add(new BufferedOutputStream(git.process().getOutputStream(), 16384));
            }
            for (var git : processes) {
                futures.add(readers.submit(() -> {
                    try (var input = git.process().getInputStream()) {
                        new GitLogParser().parse(input, commit -> {
                            synchronized (consumer) {
                                consumer.accept(commit);
                            }
                        });
                        requireSuccess(git);
                    } catch (IOException | RuntimeException error) {
                        var diagnostic = new IOException("parallel diff failed: " + error.getMessage(), error);
                        failure.compareAndSet(null, diagnostic);
                        closeProcesses(processes);
                    } catch (Error error) {
                        closeProcesses(processes);
                        throw error;
                    }
                }));
            }
            var arguments = commonArguments(configuration);
            arguments.addAll(List.of(
                "log",
                "--no-merges",
                "--no-patch",
                "--no-notes",
                "--format=%H",
                repository.revision(),
                "--"
            ));
            try (var traversal = new GitProcess(repository.path(), arguments, true)) {
                var source = new InputStreamReader(
                    traversal.process().getInputStream(),
                    StandardCharsets.US_ASCII
                );
                try (var input = new BufferedReader(source)) {
                    int next = 0;
                    String id;
                    while ((id = input.readLine()) != null) {
                        validateId(id);
                        var output = outputs.get(next);
                        output.write(id.getBytes(StandardCharsets.US_ASCII));
                        output.write('\n');
                        next = (next + 1) % count;
                    }
                    requireSuccess(traversal);
                }
            }
            for (var output : outputs) {
                output.close();
            }
            for (var future : futures) {
                future.get();
            }
            if (failure.get() != null) {
                throw failure.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("parallel Git analysis was interrupted", interrupted);
        } catch (ExecutionException error) {
            throw new IOException("parallel Git reader failed", error.getCause());
        } catch (IOException error) {
            if (failure.get() != null) {
                throw failure.get();
            }
            throw error;
        } finally {
            closeProcesses(processes);
            readers.shutdownNow();
        }
    }

    /**
     * Preserves root/empty commits, canonical author placeholders, recursive diffs
     * and the serial engine's rename threshold. --always is essential: otherwise
     * an empty diff-tree result would silently omit the commit from aggregation.
     * @param configuration shared temporary binary rules
     * @return complete persistent diff-tree arguments
     */
    private List<String> diffArguments(List<String> configuration) {
        var arguments = commonArguments(configuration);
        arguments.addAll(List.of(
            "diff-tree",
            "--stdin",
            "--root",
            "--always",
            "-r",
            "--no-ext-diff",
            "--no-textconv",
            "--no-color",
            "--no-notes",
            "--encoding=UTF-8",
            "--find-renames=50%",
            "--numstat",
            "-z",
            "--format=%x00GL%x00%aN%x00%aE%x00"
        ));
        return arguments;
    }

    /**
     * Applies the same algorithm and per-process memory settings as serial log.
     * These are not aggregate RAM limits: every worker has independent Git caches.
     * @param configuration validated binary overrides
     * @return mutable configuration prefix
     */
    private ArrayList<String> commonArguments(List<String> configuration) {
        var arguments = new ArrayList<>(configuration);
        arguments.addAll(List.of(
            "-c",
            "log.showSignature=false",
            "-c",
            "diff.algorithm=myers",
            "-c",
            "core.packedGitWindowSize=8m",
            "-c",
            "core.packedGitLimit=32m",
            "-c",
            "core.deltaBaseCacheLimit=32m"
        ));
        return arguments;
    }

    /**
     * Accepts only native full SHA-1/SHA-256 IDs on the diff-tree input protocol.
     * @param id one streamed revision ID
     * @throws IOException if traversal produced malformed output
     */
    private void validateId(String id) throws IOException {
        if (id.length() != 40 && id.length() != 64) {
            throw new IOException("invalid Git revision stream");
        }
        for (int index = 0; index < id.length(); index++) {
            char value = id.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                throw new IOException("invalid Git revision stream");
            }
        }
    }

    /**
     * Rejects a failed child before publishing final aggregate results.
     * @param git owned traversal or worker
     * @throws IOException if Git exits unsuccessfully
     */
    private void requireSuccess(GitProcess git) throws IOException {
        int status = git.await();
        if (status != 0) {
            throw new IOException("git exited with status " + status);
        }
    }

    /**
     * Kills all owned children to release blocked pipe writers and readers.
     * Safe repeated closure is provided by GitProcess's shutdown-hook ownership.
     * @param processes fixed list, populated before reader tasks start
     */
    private static void closeProcesses(List<GitProcess> processes) {
        for (var git : processes) {
            git.close();
        }
    }
}
