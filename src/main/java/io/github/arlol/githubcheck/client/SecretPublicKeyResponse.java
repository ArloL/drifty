package io.github.arlol.githubcheck.client;

public record SecretPublicKeyResponse(
		String keyId,
		String key
) {
}
