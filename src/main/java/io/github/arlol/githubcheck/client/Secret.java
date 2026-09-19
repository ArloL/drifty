package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/actions/secrets/{secret_name}"
)
public record Secret(
		String name,
		String createdAt,
		String updatedAt
) {
}
