package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum PagesBuildType {

	@JsonProperty("workflow")
	WORKFLOW,

	@JsonProperty("legacy")
	LEGACY;

}
