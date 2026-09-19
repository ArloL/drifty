package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		request = { "PUT /repos/{owner}/{repo}/actions/secrets/{secret_name}",
				"PUT /repos/{owner}/{repo}/environments/{environment_name}/secrets/{secret_name}" }
)
public record SecretRequest(
		String encryptedValue,
		String keyId
) {
}
