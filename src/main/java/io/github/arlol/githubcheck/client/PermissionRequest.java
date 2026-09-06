package io.github.arlol.githubcheck.client;

/**
 * Body of the collaborator and team-repository PUTs: the permission to grant.
 */
public record PermissionRequest(
		String permission
) {
}
