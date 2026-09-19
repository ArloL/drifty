package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /orgs/{org}/code-security/configurations/defaults}: a
 * configuration and the kind of new repository it is the default for.
 */
@GitHubEndpoint(
		response = "GET /orgs/{org}/code-security/configurations/defaults",
		unmanaged = {
				"configuration.created_at — when GitHub made the configuration, not a setting",
				"configuration.url — navigation GitHub supplies for the configuration",
				"configuration.secret_scanning_extended_metadata — no established default; FOLLOWUPS.md entry 3",
				"configuration.secret_scanning_delegated_bypass_options.reviewers.security_configuration_id — the configuration being read, as on the configuration response" }
)
public record CodeSecurityDefaultResponse(
		String defaultForNewRepos,
		CodeSecurityConfigurationResponse configuration
) {
}
