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
				"security_and_analysis — in the spec for the organization listing and not for this one, and GitHub returned it on none of 101 repositories; the record shares one shape for both listings" }
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
