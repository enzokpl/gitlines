package dev.gitlines.model;

/**
 * Aggregate counters across all canonical identities in the analyzed revision.
 * @param commits non-merge commits
 * @param added historical text lines added
 * @param deleted historical text lines removed
 */
public record Totals(long commits, long added, long deleted) {
    /**
     * Computes the signed historical line balance.
     * @return additions minus removals
     */
    public long net() {
        return added - deleted;
    }
}
