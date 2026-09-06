package io.github.arlol.githubcheck.actual;

import java.util.Set;

/**
 * A team on an organization with its members by role. The description is
 * {@code ""} when GitHub has none and the parent slug null when the team has no
 * parent; the id is what a child team's parent reference wants.
 */
public record ActualTeam(
		long id,
		String slug,
		String name,
		String description,
		String privacy,
		String notificationSetting,
		String parent,
		Set<String> members,
		Set<String> maintainers
) {

	public ActualTeam {
		members = Set.copyOf(members);
		maintainers = Set.copyOf(maintainers);
	}

}
