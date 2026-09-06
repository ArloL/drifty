package io.github.arlol.githubcheck.client;

public record OrgVariableResponse(
		String name,
		String value,
		String createdAt,
		String updatedAt,
		SecretVisibility visibility
) {
}
