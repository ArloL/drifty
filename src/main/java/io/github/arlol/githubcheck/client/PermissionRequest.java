package io.github.arlol.githubcheck.client;

/**
 * Body of the collaborator and team-repository PUTs: the permission to grant.
 */
@GitHubEndpoint(
		request = { "PUT /repos/{owner}/{repo}/collaborators/{username}",
				"PUT /orgs/{org}/teams/{team_slug}/repos/{owner}/{repo}" }
)
public record PermissionRequest(
		String permission
) {
}
