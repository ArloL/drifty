package io.github.arlol.githubcheck.client;

@GitHubEndpoint(response = "GET /orgs/{org}/actions/secrets/{secret_name}")
public record OrgSecretResponse(
		String name,
		String createdAt,
		String updatedAt,
		SecretVisibility visibility
) {
}
