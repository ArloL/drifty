package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum BranchPolicyType {
	@JsonProperty("branch")
	BRANCH, @JsonProperty("tag")
	TAG
}
