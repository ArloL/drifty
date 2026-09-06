package io.github.arlol.githubcheck.client;

/** Body of the variable POST and PATCH endpoints. */
public record VariableRequest(
		String name,
		String value
) {
}
