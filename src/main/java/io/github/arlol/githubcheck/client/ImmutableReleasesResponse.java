package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/immutable-releases",
		unmanaged = {
				"enforced_by_owner — set on the account above the repository, so no repository-level fix could change it" }
)
public record ImmutableReleasesResponse(
		boolean enabled
) {
}
