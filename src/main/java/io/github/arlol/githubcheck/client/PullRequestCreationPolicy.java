package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum PullRequestCreationPolicy {
	@JsonProperty("all")
	ALL, @JsonProperty("collaborators_only")
	COLLABORATORS_ONLY
}
