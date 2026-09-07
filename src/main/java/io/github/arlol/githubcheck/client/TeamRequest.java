package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the team PATCH. {@code parent_team_id} is sent even when null: that
 * is how a parent is removed, and omitting it would keep the one GitHub has.
 * The POST does not accept that null, so it uses {@link TeamCreateRequest}.
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
