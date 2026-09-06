package io.github.arlol.githubcheck.client;

/** A repository or environment Actions variable, as GitHub lists it. */
public record VariableResponse(
		String name,
		String value,
		String createdAt,
		String updatedAt
) {
}
