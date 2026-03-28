package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RulesetSourceType {
	@JsonProperty("Repository")
	REPOSITORY, @JsonProperty("Organization")
	ORGANIZATION
}
