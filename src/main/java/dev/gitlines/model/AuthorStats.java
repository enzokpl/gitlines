package dev.gitlines.model;

/**
 * Immutable historical contributions for one exact mailmap-canonicalized identity.
 * @param name canonical author name
 * @param email canonical author email, possibly empty
 * @param commits number of analyzed non-merge commits
 * @param added historical text lines added
 * @param deleted historical text lines removed
 */
public record AuthorStats(String name, String email, long commits, long added, long deleted) {
    /**
     * Computes historical additions minus removals, not current line ownership.
     * @return signed line balance
     */
    public long net() {
        return added - deleted;
    }
}
