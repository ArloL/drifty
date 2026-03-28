package io.github.arlol.githubcheck.client;

public record RepositoryPermissions(
		Boolean admin,
		Boolean maintain,
		Boolean push,
		Boolean triage,
		Boolean pull
) {
}
