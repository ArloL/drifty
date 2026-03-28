package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RulesetRuleType {
	@JsonProperty("required_status_checks")
	REQUIRED_STATUS_CHECKS, @JsonProperty("required_linear_history")
	REQUIRED_LINEAR_HISTORY, @JsonProperty("non_fast_forward")
	NON_FAST_FORWARD, @JsonProperty("pull_request")
	PULL_REQUEST, @JsonProperty("creation")
	CREATION, @JsonProperty("update")
	UPDATE, @JsonProperty("deletion")
	DELETION
}
