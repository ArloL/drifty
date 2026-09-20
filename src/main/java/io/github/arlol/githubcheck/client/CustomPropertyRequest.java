package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PUT /orgs/{org}/properties/schema/{name}}. The PUT replaces
 * the definition, so {@code default_value} and {@code description} are sent
 * even when null — omitting them would keep what GitHub had. The allowed values
 * are only carried for the select types; the other types reject them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = "PUT /orgs/{org}/properties/schema/{custom_property_name}",
		unmanaged = {
				"require_explicit_values — no drift group compares it and config/drifty.pkl has no field for it" }
)
public record CustomPropertyRequest(
		String valueType,
		boolean required,
		@JsonInclude(JsonInclude.Include.ALWAYS) @Nullable Object defaultValue,
		@JsonInclude(JsonInclude.Include.ALWAYS) @Nullable String description,
		@Nullable List<String> allowedValues,
		@Nullable String valuesEditableBy
) {

	public CustomPropertyRequest {
		allowedValues = allowedValues == null ? null
				: List.copyOf(allowedValues);
	}

}
