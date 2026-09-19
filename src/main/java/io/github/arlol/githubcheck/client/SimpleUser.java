package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /users/{username}",
		unmanaged = {
				"bio — drifty resolves a login to an id; a user's profile is not configuration it reconciles",
				"blog — profile, as above", "business_plus — profile, as above",
				"company — profile, as above", "hireable — profile, as above",
				"location — profile, as above",
				"notification_email — profile, as above",
				"twitter_username — profile, as above",
				"two_factor_authentication — a user's own account security, which no organization config can set" }
)
public record SimpleUser(
		String login,
		Long id,
		String nodeId,
		String avatarUrl,
		String gravatarId, // nullable
		String url,
		String htmlUrl,
		UserType type,
		Boolean siteAdmin,
		String name, // nullable, optional
		String email, // nullable, optional
		String userViewType // optional
) {

	public enum UserType {
		@JsonProperty("User")
		USER, @JsonProperty("Organization")
		ORGANIZATION, @JsonProperty("Bot")
		BOT
	}

}
