package io.github.arlol.githubcheck.client;

@GitHubEndpoint(response = "GET /orgs/{org}/actions/variables/{name}")
public record OrgVariableResponse(
		String name,
		String value,
		String createdAt,
		String updatedAt,
		SecretVisibility visibility
) {
}
