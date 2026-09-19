package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}",
		undocumented = {
				"has_commit_comments — GitHub returns it; the spec omits it",
				"security_and_analysis.secret_scanning_validity_checks — GitHub returns it and SecretScanningValidityChecksDriftGroup compares it; the spec omits it from this schema" },
		unmanaged = {
				"code_of_conduct — GitHub detects it from the repository's files; it is not a setting anything writes",
				"custom_properties — CustomPropertiesDriftGroup reads values from /properties/values, which is also where it writes them",
				"parent — a fork's upstream; forking is not something drifty reconciles",
				"source — a fork network's root, as above",
				"template_repository — which template the repository was created from is history, not a setting",
				"security_and_analysis.secret_scanning_delegated_bypass_options.reviewers.mode — SecretScanningDelegatedBypassDriftGroup compares a reviewer by id and type; GitHub added mode and nothing compares it yet" }
)
public record RepositoryDetailsResponse(
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
		PullRequestCreationPolicy pullRequestCreationPolicy, // optional
		Boolean hasCommitComments, // optional
		boolean allowForking,
		boolean webCommitSignoffRequired,
		boolean allowSquashMerge,
		boolean allowMergeCommit,
		boolean allowRebaseMerge,
		boolean allowAutoMerge,
		boolean deleteBranchOnMerge,
		boolean allowUpdateBranch,
		SquashMergeCommitTitle squashMergeCommitTitle,
		SquashMergeCommitMessage squashMergeCommitMessage,
		MergeCommitTitle mergeCommitTitle,
		MergeCommitMessage mergeCommitMessage,
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

	public RepositoryDetailsResponse {
		topics = topics == null ? null : List.copyOf(topics);
	}

}
