package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the team POST and PATCH. {@code parent_team_id} is sent even when
 * null: that is how a parent is removed, and omitting it would keep the one
 * GitHub has.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamRequest(
		String name,
		String description,
		String privacy,
		String notificationSetting,
		@JsonInclude(JsonInclude.Include.ALWAYS) Long parentTeamId
) {
}
