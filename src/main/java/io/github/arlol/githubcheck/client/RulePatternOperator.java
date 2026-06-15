package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RulePatternOperator {
	@JsonProperty("starts_with")
	STARTS_WITH, @JsonProperty("ends_with")
	ENDS_WITH, @JsonProperty("contains")
	CONTAINS, @JsonProperty("regex")
	REGEX
}
