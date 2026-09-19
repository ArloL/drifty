package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/code-scanning/default-setup",
		unmanaged = {
				"languages — drifty turns default setup on and off; which languages CodeQL scans is GitHub's own detection, which changes as a repository does",
				"query_suite — part of how default setup runs rather than whether it is on",
				"runner_label — how it runs, as above",
				"runner_type — how it runs, as above",
				"schedule — how it runs, as above",
				"threat_model — how it runs, as above" }
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
