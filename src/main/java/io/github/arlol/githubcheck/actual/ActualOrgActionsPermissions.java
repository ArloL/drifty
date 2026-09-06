package io.github.arlol.githubcheck.actual;

import java.util.List;

import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;

/**
 * The organization's Actions permissions policy. The enums are the client's:
 * they spell GitHub's contract values and {@code PklTypes} maps the config onto
 * the same ones.
 *
 * @param selectedActions      null unless allowedActions is SELECTED; GitHub
 *                             serves the list from its own endpoint and only
 *                             that value makes it meaningful
 * @param selectedRepositories the repositories Actions is enabled in; read only
 *                             when enabledRepositories is SELECTED and empty
 *                             otherwise
 */
public record ActualOrgActionsPermissions(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		boolean shaPinningRequired,
		ActualSelectedActions selectedActions,
		List<String> selectedRepositories
) {

	public ActualOrgActionsPermissions {
		selectedRepositories = List.copyOf(selectedRepositories);
	}

	/** A policy without a repository selection. */
	public ActualOrgActionsPermissions(
			ActionsEnabledRepositories enabledRepositories,
			AllowedActions allowedActions,
			boolean shaPinningRequired,
			ActualSelectedActions selectedActions
	) {
		this(
				enabledRepositories,
				allowedActions,
				shaPinningRequired,
				selectedActions,
				List.of()
		);
	}

}
