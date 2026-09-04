package io.github.arlol.githubcheck.client;

public record OrgSecretResponse(
		String name,
		String createdAt,
		String updatedAt,
		SecretVisibility visibility
) {
}
