package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * {@code GET|PUT /orgs/{org}/actions/permissions/selected-actions}. Same shape
 * in the response and the request, like {@link WorkflowPermissions}.
 */
public record SelectedActions(
		boolean githubOwnedAllowed,
		boolean verifiedAllowed,
		List<String> patternsAllowed
) {

	public SelectedActions {
		patternsAllowed = patternsAllowed == null ? List.of()
				: List.copyOf(patternsAllowed);
	}

}
