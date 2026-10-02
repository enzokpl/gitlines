package dev.gitlines.analysis;

import dev.gitlines.git.GitClient;
import dev.gitlines.git.RepositoryInfoReader;
import dev.gitlines.model.AnalysisResult;
import dev.gitlines.model.AuthorStats;
import dev.gitlines.model.Totals;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

/**
 * Aggregates streaming commit contributions with memory proportional to authors.
 */
public final class RepositoryAnalyzer {
    /**
     * Analyzes the pinned HEAD with two diff workers, ordering by activity then
     * exact canonical identity.
     * @param directory repository root or subdirectory
     * @return a complete immutable result, never a partial failed analysis
     * @throws IOException if repository inspection or history traversal fails
     */
    public AnalysisResult analyze(Path directory) throws IOException {
        return analyze(directory, 2);
    }

    /**
     * Runs the bounded worker mode without changing aggregation or
     * ordering. GitClient serializes callbacks even when diffs finish concurrently.
     * @param directory repository root or subdirectory
     * @param workers number of Git workers, from one to four
     * @return complete immutable analysis
     * @throws IOException if any subprocess or protocol fails
     */
    public AnalysisResult analyze(Path directory, int workers) throws IOException {
        var repository = new RepositoryInfoReader().read(directory);
        var counters = new HashMap<Identity, long[]>();
        new GitClient().contributions(repository, workers, commit -> {
            var key = new Identity(commit.name(), commit.email());
            var counts = counters.computeIfAbsent(key, ignored -> new long[3]);
            counts[0] = Math.addExact(counts[0], commit.commits());
            counts[1] = Math.addExact(counts[1], commit.added());
            counts[2] = Math.addExact(counts[2], commit.deleted());
        });
        var authors = new ArrayList<AuthorStats>(counters.size());
        long commits = 0;
        long added = 0;
        long deleted = 0;
        for (var entry : counters.entrySet()) {
            var identity = entry.getKey();
            var counts = entry.getValue();
            authors.add(new AuthorStats(identity.name(), identity.email(), counts[0], counts[1], counts[2]));
            commits = Math.addExact(commits, counts[0]);
            added = Math.addExact(added, counts[1]);
            deleted = Math.addExact(deleted, counts[2]);
        }
        var activityOrder = Comparator.comparingLong(
            (AuthorStats author) -> Math.addExact(author.added(), author.deleted())
        )
            .reversed()
            .thenComparing(AuthorStats::name)
            .thenComparing(AuthorStats::email);
        authors.sort(activityOrder);
        return new AnalysisResult(repository, new Totals(commits, added, deleted), authors);
    }

    /**
     * Exact canonical identity; no case folding or heuristic author merging.
     * @param name Git-canonicalized name
     * @param email Git-canonicalized email
     */
    private record Identity(String name, String email) {
    }
}
