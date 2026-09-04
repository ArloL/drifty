package io.github.arlol.githubcheck.actual;

import java.util.List;

/**
 * The organization's allow-list of Actions, read only when
 * {@code allowedActions} is {@code SELECTED}.
 */
public record ActualSelectedActions(
		boolean githubOwnedAllowed,
		boolean verifiedAllowed,
		List<String> patternsAllowed
) {

	public ActualSelectedActions {
		patternsAllowed = List.copyOf(patternsAllowed);
	}

}
