package dev.gitlines.git;

import dev.gitlines.model.AuthorStats;
import dev.gitlines.model.RepositoryInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs Git's native diff engine and mailmap resolution for the pinned revision.
 * Pack mappings and the delta-base cache are bounded for this subprocess only;
 * large decoded objects and Git's diff working memory remain outside these limits.
 */
public final class GitClient {
    /**
     * Streams non-merge contributions, disabling external diff and text conversion.
     * Optional binary preparation avoids full large-object inflation for numstat;
     * only fully validated binary paths receive temporary subprocess attributes.
     * Inconclusive discovery preserves ordinary Git classification and counts.
     * @param repository metadata with the immutable revision to traverse
     * @param consumer recipient of per-commit counters
     * @throws IOException if parsing or Git execution fails
     */
    public void contributions(RepositoryInfo repository, Consumer<AuthorStats> consumer) throws IOException {
        contributions(repository, 1, consumer);
    }

    /**
     * Shares binary preparation across persistent workers. One worker retains the
     * established serial log as the benchmark control; multiple workers partition
     * non-merge commit IDs and keep the same native diff and author semantics.
     * @param repository pinned metadata
     * @param workers bounded worker count, from one to four
     * @param consumer serialized recipient of complete commit counters
     * @throws IOException if any traversal or diff fails
     */
    public void contributions(
        RepositoryInfo repository,
        int workers,
        Consumer<AuthorStats> consumer
    ) throws IOException {
        if (workers < 1 || workers > 4) {
            throw new IllegalArgumentException("workers must be between 1 and 4");
        }
        if (repository.revision() == null) {
            return;
        }
        try (var preflight = new BinaryPreflight(repository)) {
            var configuration = preflight.configuration();
            if (workers > 1) {
                new ParallelDiffs().contributions(repository, configuration, workers, consumer);
                return;
            }
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
                "core.deltaBaseCacheLimit=32m",
                "log",
                "--no-merges",
                "--root",
                "--no-ext-diff",
                "--no-textconv",
                "--no-color",
                "--no-notes",
                "--encoding=UTF-8",
                "--use-mailmap",
                "--find-renames=50%",
                "--numstat",
                "-z",
                "--format=%x00GL%x00%aN%x00%aE%x00",
                repository.revision(),
                "--"
            ));
            try (var git = new GitProcess(repository.path(), arguments, true);
                var input = git.process().getInputStream()) {
                new GitLogParser().parse(input, consumer);
                int status = git.await();
                if (status != 0) {
                    throw new IOException("failed to analyze repository: git exited with status " + status);
                }
            }
        }
    }
}
