package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code PUT /orgs/{org}/actions/permissions}. {@code
 * enabledRepositories} is required by the API even when only {@code
 * allowedActions} or {@code shaPinningRequired} drifted, so it is always sent;
 * the other two stay nullable so an unset one is left as GitHub has it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgActionsPermissionsRequest(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		Boolean shaPinningRequired
) {
}
