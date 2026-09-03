package io.github.arlol.githubcheck.actual;

import java.util.List;

import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;

/**
 * The repository settings drifty manages, as they are on GitHub.
 * <p>
 * GitHub's repository response carries some seventy fields — URLs, counters,
 * timestamps — and reports an unset description or homepage as {@code null}
 * where the config says {@code ""}. Picking out the managed settings and
 * normalising the empties is the client's business, see {@code ActualTypes};
 * the comparison sees the same vocabulary the config is written in. The enums
 * are the client's because they spell GitHub's contract values and
 * {@code PklTypes} maps the config onto the same ones.
 *
 * @param organizationOwned whether an organisation owns the repository, which
 *                          decides whether {@code allow_forking} is managed:
 *                          GitHub only exposes it there
 */
public record ActualRepository(
		boolean archived,
		boolean organizationOwned,
		String description,
		String homepage,
		RepositoryVisibility visibility,
		String defaultBranch,
		List<String> topics,
		boolean hasIssues,
		boolean hasProjects,
		boolean hasWiki,
		boolean hasDiscussions,
		boolean isTemplate,
		boolean allowForking,
		boolean webCommitSignoffRequired,
		boolean allowMergeCommit,
		boolean allowSquashMerge,
		boolean allowRebaseMerge,
		boolean allowAutoMerge,
		boolean allowUpdateBranch,
		boolean deleteBranchOnMerge,
		SquashMergeCommitTitle squashMergeCommitTitle,
		SquashMergeCommitMessage squashMergeCommitMessage,
		MergeCommitTitle mergeCommitTitle,
		MergeCommitMessage mergeCommitMessage
) {

	public ActualRepository {
		topics = List.copyOf(topics);
	}

}
