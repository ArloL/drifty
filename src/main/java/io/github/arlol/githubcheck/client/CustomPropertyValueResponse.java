package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /repos/{owner}/{repo}/properties/values}. The value
 * is a string, a list of strings for a {@code multi_select} property, or null
 * when the property has no value on this repository.
 */
public record CustomPropertyValueResponse(
		String propertyName,
		Object value
) {
}
