package io.github.arlol.githubcheck.client;

public record OrgActionsPermissionsResponse(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		Boolean shaPinningRequired
) {
}
