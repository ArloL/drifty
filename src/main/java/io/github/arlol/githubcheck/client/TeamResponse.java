package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

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
