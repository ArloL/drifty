package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(response = "GET /repos/{owner}/{repo}/branches")
public record BranchResponse(
		String name,
		@JsonProperty("protected") boolean isProtected
) {
}
