package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of {@code GET /orgs/{org}/actions/runner-groups}. */
public record RunnerGroupResponse(
		long id,
		String name,
		String visibility,
		@JsonProperty("default") Boolean isDefault,
		Boolean inherited,
		Boolean allowsPublicRepositories,
		Boolean restrictedToWorkflows,
		List<String> selectedWorkflows
) {

	public RunnerGroupResponse {
		selectedWorkflows = selectedWorkflows == null ? List.of()
				: List.copyOf(selectedWorkflows);
	}

}
