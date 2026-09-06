package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /orgs/{org}/code-security/configurations/defaults}: a
 * configuration and the kind of new repository it is the default for.
 */
public record CodeSecurityDefaultResponse(
		String defaultForNewRepos,
		CodeSecurityConfigurationResponse configuration
) {
}
