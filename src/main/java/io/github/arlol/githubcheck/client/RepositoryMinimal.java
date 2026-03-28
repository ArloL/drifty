package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RepositoryMinimal(
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
		String pullRequestCreationPolicy, // optional
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

	public RepositoryMinimal {
		topics = topics == null ? null : List.copyOf(topics);
	}

}
