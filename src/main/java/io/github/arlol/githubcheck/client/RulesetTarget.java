package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RulesetTarget {
	@JsonProperty("branch")
	BRANCH, @JsonProperty("tag")
	TAG, @JsonProperty("push")
	PUSH
}
