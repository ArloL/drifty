package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ActionsEnabledRepositories {

	@JsonProperty("all")
	ALL,

	@JsonProperty("none")
	NONE,

	@JsonProperty("selected")
	SELECTED

}
