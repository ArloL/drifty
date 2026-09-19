package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(response = "GET /users/{username}")
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
