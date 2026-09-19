package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/actions/secrets/public-key"
)
public record SecretPublicKeyResponse(
		String keyId,
		String key
) {
}
