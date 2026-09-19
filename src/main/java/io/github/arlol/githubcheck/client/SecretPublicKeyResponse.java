package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/actions/secrets/public-key",
		unmanaged = {
				"title — the key is used to encrypt a secret and discarded; nothing names it" }
)
public record SecretPublicKeyResponse(
		String keyId,
		String key
) {
}
