package io.github.arlol.githubcheck.client;

import org.jspecify.annotations.Nullable;

/**
 * The token's own account, as {@code GET /user} answers it: the one response
 * that carries a personal account's {@code plan}.
 * <p>
 * Its own record rather than a field on {@link SimpleUser}, which is also the
 * shape of every user nested in a team, a collaborator listing or a branch
 * protection — none of which carries a plan. Named against {@code GET
 * /users/{username}}, the schema GitHub documents for a user and the one the
 * contract holds; {@code GET /user} answers a superset of it.
 */
@GitHubEndpoint(
		response = "GET /users/{username}",
		unmanaged = {
				"bio — read for the plan alone; a user's profile is not configuration drifty reconciles",
				"blog — profile, as above", "business_plus — profile, as above",
				"company — profile, as above", "email — profile, as above",
				"hireable — profile, as above", "location — profile, as above",
				"name — profile, as above",
				"notification_email — profile, as above",
				"twitter_username — profile, as above",
				"two_factor_authentication — a user's own account security, which no config can set",
				"plan.collaborators — a plan's limits are billing, not configuration; only its name says what a private repository can have",
				"plan.private_repos — a plan's limit, as above",
				"plan.space — a plan's limit, as above" }
)
public record AuthenticatedUserResponse(
		String login,
		@Nullable AccountPlan plan
) {
}
