package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/code-scanning/default-setup"
)
public record CodeScanningDefaultSetupResponse(
		State state
) {

	public boolean isEnabled() {
		return State.NOT_CONFIGURED != state;
	}

	public enum State {
		@JsonProperty("configured")
		CONFIGURED, @JsonProperty("not-configured")
		NOT_CONFIGURED
	}

}
