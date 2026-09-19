package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = { "GET /user/repos", "GET /orgs/{org}/repos" },
		undocumented = {
				"has_commit_comments — GitHub returns it; the spec omits it from this listing",
				"license.html_url — GitHub returns it on the nested license object; the spec omits it",
				"network_count — GitHub returns it; the spec's Minimal Repository omits it",
				"role_name — GitHub returns it; the spec's Minimal Repository omits it",
				"subscribers_count — GitHub returns it; the spec's Minimal Repository omits it",
				"security_and_analysis.secret_scanning_validity_checks — GitHub returns it and the spec omits it from this schema",
				"security_and_analysis — in the spec for the organization listing and not for this one, and GitHub returned it on none of 101 repositories; the record shares one shape for both listings" },
		unmanaged = {
				"code_of_conduct — GitHub detects it from the repository's files",
				"custom_properties — read from /properties/values, which is also where drifty writes them",
				"security_and_analysis.secret_scanning_delegated_bypass_options.reviewers.mode — compared by id and type; nothing compares mode yet",
				"allow_auto_merge — the listing is read for archived and nothing else; RepoSettingsDriftGroup compares the merge settings off GET /repos/{owner}/{repo}, the one response that carries them for every account",
				"allow_merge_commit — a merge setting read from the details response, as above",
				"allow_rebase_merge — a merge setting read from the details response, as above",
				"allow_squash_merge — a merge setting read from the details response, as above",
				"allow_update_branch — a merge setting read from the details response, as above",
				"merge_commit_message — a merge setting read from the details response, as above",
				"merge_commit_title — a merge setting read from the details response, as above",
				"squash_merge_commit_message — a merge setting read from the details response, as above",
				"squash_merge_commit_title — a merge setting read from the details response, as above",
				"anonymous_access_enabled — Enterprise Server only; no drift group compares it",
				"code_search_index_status — GitHub's indexing progress, not configuration" }
)
public record RepositorySummaryResponse(
		Long id,
		String nodeId,
		String name,
		String fullName,
		SimpleUser owner,
		@JsonProperty("private") Boolean isPrivate,
		String htmlUrl,
		String description, // nullable
		Boolean fork,
		String url,
		String gitUrl, // optional
		String sshUrl, // optional
		String cloneUrl, // optional
		String svnUrl, // optional
		String mirrorUrl, // nullable, optional
		String hooksUrl,
		String homepage, // nullable, optional
		String language, // nullable, optional
		boolean archived,
		Boolean disabled, // optional
		RepositoryVisibility visibility,
		String defaultBranch, // optional
		List<String> topics, // optional
		Integer forksCount, // optional
		Integer stargazersCount, // optional
		Integer watchersCount, // optional
		Integer size, // optional
		Integer openIssuesCount, // optional
		Boolean isTemplate, // optional
		Boolean hasIssues, // optional
		Boolean hasProjects, // optional
		Boolean hasWiki, // optional
		Boolean hasPages, // optional
		Boolean hasDiscussions, // optional
		Boolean hasPullRequests, // optional
		PullRequestCreationPolicy pullRequestCreationPolicy, // optional
		Boolean hasCommitComments, // optional
		Boolean allowForking, // optional
		Boolean webCommitSignoffRequired, // optional
		String pushedAt, // nullable, optional
		String createdAt, // nullable, optional
		String updatedAt, // nullable, optional
		RepositoryPermissions permissions, // optional
		String roleName, // optional
		String tempCloneToken, // optional
		Boolean deleteBranchOnMerge, // optional
		Integer subscribersCount, // optional
		Integer networkCount, // optional
		License license, // nullable, optional
		Integer forks, // optional
		Integer openIssues, // optional
		Integer watchers, // optional
		SecurityAndAnalysis securityAndAnalysis // nullable, optional
) {

	public RepositorySummaryResponse {
		topics = topics == null ? null : List.copyOf(topics);
	}

}
