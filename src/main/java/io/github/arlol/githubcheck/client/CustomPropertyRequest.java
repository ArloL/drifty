package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code PUT /orgs/{org}/properties/schema/{name}}. The PUT replaces
 * the definition, so {@code default_value} and {@code description} are sent
 * even when null — omitting them would keep what GitHub had. The allowed values
 * are only carried for the select types; the other types reject them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomPropertyRequest(
		String valueType,
		boolean required,
		@JsonInclude(JsonInclude.Include.ALWAYS) Object defaultValue,
		@JsonInclude(JsonInclude.Include.ALWAYS) String description,
		List<String> allowedValues,
		String valuesEditableBy
) {

	public CustomPropertyRequest {
		allowedValues = allowedValues == null ? null
				: List.copyOf(allowedValues);
	}

}
