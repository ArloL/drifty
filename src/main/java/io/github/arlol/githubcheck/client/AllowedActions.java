package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AllowedActions {

	@JsonProperty("all")
	ALL,

	@JsonProperty("local_only")
	LOCAL_ONLY,

	@JsonProperty("selected")
	SELECTED

}
