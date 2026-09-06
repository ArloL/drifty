package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * A custom property definition as {@code GET /orgs/{org}/properties/schema}
 * returns it. {@code default_value} is a string, or a list of strings for a
 * {@code multi_select} property, or null; Jackson leaves it as whichever it
 * finds and {@code ActualTypes} sorts it out. {@code source_type} says whether
 * the organization or its enterprise owns the definition.
 */
public record CustomPropertyResponse(
		String propertyName,
		String sourceType,
		String valueType,
		Boolean required,
		Object defaultValue,
		String description,
		List<String> allowedValues,
		String valuesEditableBy
) {

	public CustomPropertyResponse {
		allowedValues = allowedValues == null ? null
				: List.copyOf(allowedValues);
	}

}
