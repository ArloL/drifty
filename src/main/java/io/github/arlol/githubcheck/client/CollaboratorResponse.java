package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /repos/{owner}/{repo}/collaborators}. The five
 * permission booleans are what the access level is read from; {@code
 * role_name} is the same thing in GitHub's other vocabulary (read, write) and
 * is the name of a custom role when one is assigned.
 */
@GitHubEndpoint(response = "GET /repos/{owner}/{repo}/collaborators")
public record CollaboratorResponse(
		String login,
		String roleName,
		Permissions permissions
) {
}
