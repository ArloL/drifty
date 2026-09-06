package io.github.arlol.githubcheck.actual;

import java.util.List;

import io.github.arlol.githubcheck.client.SecretVisibility;

/**
 * An organization Actions variable. The org twin of {@link ActualVariable} with
 * the visibility of {@link ActualOrgSecret}; the repository names behind a
 * {@code selected} visibility come from a second request.
 */
public record ActualOrgVariable(
		String name,
		String value,
		SecretVisibility visibility,
		List<String> selectedRepositories
) {

	public ActualOrgVariable {
		selectedRepositories = List.copyOf(selectedRepositories);
	}

}
