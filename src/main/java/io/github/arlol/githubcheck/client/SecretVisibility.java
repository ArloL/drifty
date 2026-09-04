package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SecretVisibility {

	@JsonProperty("all")
	ALL,

	@JsonProperty("private")
	PRIVATE,

	@JsonProperty("selected")
	SELECTED

}
