package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/branches",
		unmanaged = { "commit — the branch's head, which moves with every push",
				"protection — BranchProtectionDriftGroup reads protection from its own endpoint, which carries the whole rule rather than this summary" }
)
public record BranchResponse(
		String name,
		@JsonProperty("protected") boolean isProtected
) {
}
