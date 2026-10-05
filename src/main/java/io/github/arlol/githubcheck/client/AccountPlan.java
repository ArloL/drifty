package io.github.arlol.githubcheck.client;

/**
 * An account's billing plan, which GitHub shows only to the account itself
 * ({@code GET /user}) and to an organization's owners ({@code GET
 * /orgs/{org}}).
 * <p>
 * Read for {@code name} alone: {@code "free"} is what says a private repository
 * of that account can have no wiki, and nothing else here is configuration.
 */
public record AccountPlan(
		String name
) {
}
