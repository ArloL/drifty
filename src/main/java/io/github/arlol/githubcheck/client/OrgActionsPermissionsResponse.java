package io.github.arlol.githubcheck.client;

@GitHubEndpoint(response = "GET /orgs/{org}/actions/permissions")
public record OrgActionsPermissionsResponse(
		ActionsEnabledRepositories enabledRepositories,
		AllowedActions allowedActions,
		Boolean shaPinningRequired
) {
}
