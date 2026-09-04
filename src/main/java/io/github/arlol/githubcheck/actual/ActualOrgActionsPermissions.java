package io.github.arlol.githubcheck.actual;

import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;

/**
 * The organization's Actions permissions policy. The enums are the client's:
 * they spell GitHub's contract values and {@code PklTypes} maps the config onto
 * the same ones.
 */
public record ActualOrgActionsPermissions(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		boolean shaPinningRequired,
		/**
		 * Null unless allowedActions is SELECTED; GitHub serves the list from
		 * its own endpoint and only that value makes it meaningful.
		 */
		ActualSelectedActions selectedActions
) {
}
