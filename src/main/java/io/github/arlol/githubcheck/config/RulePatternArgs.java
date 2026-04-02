package io.github.arlol.githubcheck.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RulePatternArgs(
		String name,
		boolean negate,
		PatternOperator operator,
		String pattern
) {

	public enum PatternOperator {
		@JsonProperty("starts_with")
		STARTS_WITH, @JsonProperty("ends_with")
		ENDS_WITH, @JsonProperty("contains")
		CONTAINS, @JsonProperty("regex")
		REGEX
	}

}
