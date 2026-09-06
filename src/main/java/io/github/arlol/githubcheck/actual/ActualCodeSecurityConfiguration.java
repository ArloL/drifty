package io.github.arlol.githubcheck.actual;

import java.util.Map;
import java.util.Set;

/**
 * A code security configuration on an organization. The security settings sit
 * in one map keyed by GitHub's field name, since all seventeen are the same
 * three-valued toggle and the drift group compares them from a table; the
 * default-for-new-repositories value and the attached repositories come from
 * their own requests. The description is {@code ""} when GitHub has none.
 */
public record ActualCodeSecurityConfiguration(
		long id,
		String name,
		String description,
		Map<String, String> settings,
		String enforcement,
		String defaultForNewRepos,
		Set<String> repositories
) {

	public ActualCodeSecurityConfiguration {
		settings = Map.copyOf(settings);
		repositories = Set.copyOf(repositories);
	}

}
