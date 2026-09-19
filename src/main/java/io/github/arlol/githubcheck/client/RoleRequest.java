package io.github.arlol.githubcheck.client;

/** Body of the organization and team membership PUTs: the role to give. */
@GitHubEndpoint(
		request = { "PUT /orgs/{org}/teams/{team_slug}/memberships/{username}",
				"PUT /orgs/{org}/memberships/{username}" }
)
public record RoleRequest(
		String role
) {
}
