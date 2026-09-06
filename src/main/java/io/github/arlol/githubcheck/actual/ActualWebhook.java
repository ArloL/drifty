package io.github.arlol.githubcheck.actual;

import java.util.Set;

/**
 * A webhook as it exists on GitHub. The url is its identity on the config side;
 * the id is what the update and delete endpoints want. GitHub never returns the
 * secret, only whether one is set, so {@code hasSecret} and the
 * {@code updatedAt} the state file compares against are all drifty gets.
 */
public record ActualWebhook(
		long id,
		String url,
		String contentType,
		boolean insecureSsl,
		boolean active,
		Set<String> events,
		boolean hasSecret,
		String updatedAt
) {

	public ActualWebhook {
		events = Set.copyOf(events);
	}

}
