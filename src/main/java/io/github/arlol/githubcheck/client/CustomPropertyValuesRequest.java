package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * Body of {@code PATCH /repos/{owner}/{repo}/properties/values}: the properties
 * to set. A value is a string or, for a {@code multi_select} property, a list
 * of strings.
 */
public record CustomPropertyValuesRequest(
		List<Property> properties
) {

	public CustomPropertyValuesRequest {
		properties = List.copyOf(properties);
	}

	public record Property(
			String propertyName,
			Object value
	) {
	}

}
