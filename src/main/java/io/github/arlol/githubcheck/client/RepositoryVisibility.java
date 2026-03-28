package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum RepositoryVisibility {
	@JsonProperty("public")
	PUBLIC, @JsonProperty("private")
	PRIVATE, @JsonProperty("internal")
	INTERNAL
}
