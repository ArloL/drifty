package io.github.arlol.githubcheck.client;

/** Body of {@code PUT .../code-security/configurations/{id}/defaults}. */
@GitHubEndpoint(
		request = "PUT /orgs/{org}/code-security/configurations/{configuration_id}/defaults"
)
public record CodeSecurityDefaultsRequest(
		String defaultForNewRepos
) {
}
