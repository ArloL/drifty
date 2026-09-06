package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /repos/{owner}/{repo}/teams}. {@code permission} is
 * the legacy three-valued field; the booleans carry triage and maintain too.
 * {@code access_source} says whether the team was granted access directly or
 * reaches the repository through its organization or enterprise.
 */
public record RepoTeamResponse(
		String slug,
		String permission,
		Permissions permissions,
		String accessSource
) {
}
