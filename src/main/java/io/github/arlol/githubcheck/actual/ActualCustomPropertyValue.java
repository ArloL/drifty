package io.github.arlol.githubcheck.actual;

import java.util.List;

/**
 * The value of one custom property on a repository. A {@code multi_select}
 * property's values arrive in {@code values} and every other type's in
 * {@code value}; a property GitHub lists with no value has neither.
 */
public record ActualCustomPropertyValue(
		String name,
		String value,
		List<String> values
) {

	public ActualCustomPropertyValue {
		values = List.copyOf(values);
	}

}
