package io.github.arlol.githubcheck.actual;

import java.util.Map;

/**
 * Who has direct access to a repository: collaborators by login and teams by
 * slug, each with the permission in the config's vocabulary ({@code pull},
 * {@code triage}, {@code push}, {@code maintain}, {@code admin}). A member who
 * reaches the repository through an org role is in neither map.
 */
public record ActualCollaborators(
		Map<String, String> users,
		Map<String, String> teams
) {

	public ActualCollaborators {
		users = Map.copyOf(users);
		teams = Map.copyOf(teams);
	}

}
