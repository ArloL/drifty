package io.github.arlol.githubcheck.actual;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A custom property definition on an organization. {@code defaultValues} is the
 * default of a {@code multi_select} property and {@code defaultValue} that of
 * every other type; the one that does not apply is empty or null. The
 * description is {@code ""} when GitHub has none, the way a repository's is.
 */
public record ActualCustomProperty(
		String name,
		String valueType,
		boolean required,
		@Nullable String defaultValue,
		List<String> defaultValues,
		String description,
		List<String> allowedValues,
		String valuesEditableBy
) {

	public ActualCustomProperty {
		defaultValues = List.copyOf(defaultValues);
		allowedValues = List.copyOf(allowedValues);
	}

}
