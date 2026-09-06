package io.github.arlol.githubcheck.client;

/** Body of the organization and team membership PUTs: the role to give. */
public record RoleRequest(
		String role
) {
}
