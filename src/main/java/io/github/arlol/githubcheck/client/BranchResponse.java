package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BranchResponse(
		String name,
		@JsonProperty("protected") boolean isProtected
) {
}
