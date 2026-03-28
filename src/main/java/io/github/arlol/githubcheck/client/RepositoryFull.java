package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepositoryFull(
		long id,
		String nodeId,
		String name,
		String fullName,
		SimpleUser owner,
		@JsonProperty("private") boolean isPrivate,
		String htmlUrl,
		String description, // nullable
		boolean fork,
		String url,
		String gitUrl,
		String sshUrl,
		String cloneUrl,
		String svnUrl,
		String mirrorUrl, // nullable
		String hooksUrl,
		String homepage, // nullable
		String language, // nullable
		boolean archived,
		boolean disabled,
		boolean isTemplate,
		RepositoryVisibility visibility,
		String defaultBranch,
		List<String> topics,
		Integer forksCount,
		Integer stargazersCount,
		Integer watchersCount,
		Integer size,
		Integer openIssuesCount,
		boolean hasIssues,
		boolean hasProjects,
		boolean hasWiki,
		boolean hasDiscussions,
		boolean hasPages,
		Boolean hasPullRequests, // optional
		String pullRequestCreationPolicy, // optional
		Boolean hasCommitComments, // optional
		boolean allowForking,
		boolean webCommitSignoffRequired,
		boolean allowSquashMerge,
		boolean allowMergeCommit,
		boolean allowRebaseMerge,
		boolean allowAutoMerge,
		boolean deleteBranchOnMerge,
		boolean allowUpdateBranch,
		String squashMergeCommitTitle,
		String squashMergeCommitMessage,
		String mergeCommitTitle,
		String mergeCommitMessage,
		String pushedAt,
		String createdAt,
		String updatedAt,
		RepositoryPermissions permissions, // optional
		String tempCloneToken, // nullable, optional
		Integer subscribersCount,
		Integer networkCount,
		License license, // nullable
		SimpleUser organization, // nullable, optional
		Integer forks,
		Integer openIssues,
		Integer watchers,
		Boolean anonymousAccessEnabled, // optional
		// May be absent for archived repos or repos where security features
		// are not available (e.g. private repos without GHAS).
		SecurityAndAnalysis securityAndAnalysis
) {

	public RepositoryFull {
		topics = topics == null ? null : List.copyOf(topics);
	}

}
