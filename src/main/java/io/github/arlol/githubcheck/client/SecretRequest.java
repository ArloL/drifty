package io.github.arlol.githubcheck.client;

public record SecretRequest(
		String encryptedValue,
		String keyId
) {
}
