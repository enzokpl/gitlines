package dev.gitlines.model;

import java.nio.file.Path;

/**
 * Describes the root and pinned revision being analyzed; revision is null for an
 * unborn HEAD, branch is null for detached HEAD, and shallow limits available history.
 * @param name repository directory name
 * @param path real repository root
 * @param revision full commit ID, or null without commits
 * @param branch current symbolic branch, or null when detached
 * @param shallow whether history is incomplete
 */
public record RepositoryInfo(String name, Path path, String revision, String branch, boolean shallow) {
}
