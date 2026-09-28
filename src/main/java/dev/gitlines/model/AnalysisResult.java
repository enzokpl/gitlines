package dev.gitlines.model;

import java.util.List;

/**
 * Complete analysis with contributors ordered by descending activity then identity.
 * @param repository pinned repository metadata
 * @param summary aggregate historical counters
 * @param authors immutable ordered contributions
 */
public record AnalysisResult(RepositoryInfo repository, Totals summary, List<AuthorStats> authors) {
    /**
     * Copies contributor data so renderers cannot mutate the analysis.
     * @param repository pinned metadata
     * @param summary aggregate counters
     * @param authors ordered contributors
     */
    public AnalysisResult {
        authors = List.copyOf(authors);
    }
}
