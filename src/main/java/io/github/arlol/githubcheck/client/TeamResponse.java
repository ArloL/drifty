package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /orgs/{org}/teams/{team_slug}",
		unmanaged = {
				"parent.* — a parent is compared by slug, which TeamRequest resolves to parent_team_id when it writes; the parent's own settings are its own entry's",
				"organization — the account being checked, which is how drifty reached this team" }
)
public record TeamResponse(
		long id,
		String name,
		String slug,
		String description, // nullable
		Privacy privacy,
		NotificationSetting notificationSetting,
		String permission,
		Parent parent, // nullable
		/** "organization" or "enterprise"; absent from older responses. */
		String type
) {

	public enum Privacy {
		@JsonProperty("closed")
		CLOSED, @JsonProperty("secret")
		SECRET
	}

	public enum NotificationSetting {
		@JsonProperty("notifications_enabled")
		NOTIFICATIONS_ENABLED, @JsonProperty("notifications_disabled")
		NOTIFICATIONS_DISABLED
	}

	public record Parent(
			long id,
			String slug
	) {
	}

}
