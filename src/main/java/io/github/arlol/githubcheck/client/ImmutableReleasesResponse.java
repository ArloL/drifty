package io.github.arlol.githubcheck.client;

@GitHubEndpoint(response = "GET /repos/{owner}/{repo}/immutable-releases")
public record ImmutableReleasesResponse(
		boolean enabled
) {
}
