package io.github.arlol.githubcheck.client;

/** Body of {@code PUT .../code-security/configurations/{id}/defaults}. */
public record CodeSecurityDefaultsRequest(
		String defaultForNewRepos
) {
}
