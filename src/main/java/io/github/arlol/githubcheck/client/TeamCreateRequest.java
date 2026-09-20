package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /orgs/{org}/teams}. Same fields as {@link TeamRequest},
 * but {@code parent_team_id} is omitted when null instead of being sent: the
 * POST answers 422 on an explicit null rather than reading it as "no parent",
 * so a top-level team can only be created by leaving the field out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = "POST /orgs/{org}/teams",
		unmanaged = {
				"maintainers — OrgTeamsDriftGroup writes memberships with their own request after the team exists, so a rejected membership is not reported as having failed the create",
				"parent_team_slug — the create resolves a parent to an id and sends parent_team_id",
				"repo_names — a team's repository access is written per repository, not at team creation" }
)
public record TeamCreateRequest(
		String name,
		@Nullable String description,
		@Nullable String privacy,
		@Nullable String notificationSetting,
		@Nullable Long parentTeamId
) {

	public static TeamCreateRequest from(TeamRequest team) {
		return new TeamCreateRequest(
				team.name(),
				team.description(),
				team.privacy(),
				team.notificationSetting(),
				team.parentTeamId()
		);
	}

}
