package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One entry of {@code GET /orgs/{org}/actions/runner-groups}. */
@GitHubEndpoint(
		response = "POST /orgs/{org}/actions/runner-groups",
		unmanaged = {
				"inherited_allows_public_repositories — what an inherited group allows is set where it is defined, not here",
				"network_configuration_id — hosted-compute networking, not a runner group setting any drift group compares",
				"workflow_restrictions_read_only — whether GitHub will let the restriction be changed, not the restriction itself" }
)
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
