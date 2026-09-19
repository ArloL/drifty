package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET /repos/{owner}/{repo}/teams}. {@code permission} is
 * the legacy three-valued field; the booleans carry triage and maintain too.
 * {@code access_source} says whether the team was granted access directly or
 * reaches the repository through its organization or enterprise.
 */
@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/teams",
		unmanaged = {
				"description — a repository's team access is compared by slug and permission; a team's own settings are OrgTeamsDriftGroup's",
				"name — the team's own setting, as above",
				"notification_setting — the team's own setting, as above",
				"parent — the team's own setting, as above",
				"privacy — the team's own setting, as above" }
)
public record RepoTeamResponse(
		String slug,
		String permission,
		Permissions permissions,
		String accessSource
) {
}
