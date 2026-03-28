package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RulesetEnforcement {
	@JsonProperty("active")
	ACTIVE, @JsonProperty("disabled")
	DISABLED, @JsonProperty("evaluate")
	EVALUATE
}
