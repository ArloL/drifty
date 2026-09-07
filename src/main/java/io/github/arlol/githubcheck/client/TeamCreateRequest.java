package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code POST /orgs/{org}/teams}. Same fields as {@link TeamRequest},
 * but {@code parent_team_id} is omitted when null instead of being sent: the
 * POST answers 422 on an explicit null rather than reading it as "no parent",
 * so a top-level team can only be created by leaving the field out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamCreateRequest(
		String name,
		String description,
		String privacy,
		String notificationSetting,
		Long parentTeamId
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
