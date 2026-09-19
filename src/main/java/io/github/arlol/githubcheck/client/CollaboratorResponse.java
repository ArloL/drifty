package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /repos/{owner}/{repo}/collaborators}. The five
 * permission booleans are what the access level is read from; {@code
 * role_name} is the same thing in GitHub's other vocabulary (read, write) and
 * is the name of a custom role when one is assigned.
 */
@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/collaborators",
		unmanaged = {
				"email — a collaborator is compared by login and permission; the profile is the user's",
				"name — the profile is the user's, as above" }
)
public record CollaboratorResponse(
		String login,
		String roleName,
		Permissions permissions
) {
}
