package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum EnvironmentReviewerType {
	@JsonProperty("User")
	USER, @JsonProperty("Team")
	TEAM
}
