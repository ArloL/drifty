package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the runner group POST and PATCH. The repository ids are only accepted
 * by the POST; the PATCH has its own repositories endpoint, so a PATCH body
 * leaves them null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = { "POST /orgs/{org}/actions/runner-groups",
				"PATCH /orgs/{org}/actions/runner-groups/{runner_group_id}" },
		unmanaged = {
				"network_configuration_id — hosted-compute networking, not a runner group setting any drift group compares",
				"runners — the runners in a group are registered machines, not configuration a config file can declare" },
		undocumented = {
				"selected_repository_ids — drifty sends it on the create, where the spec carries it; the PATCH schema omits it and GitHub takes the selection through its own endpoint" }
)
public record RunnerGroupRequest(
		String name,
		String visibility,
		List<Long> selectedRepositoryIds,
		boolean allowsPublicRepositories,
		boolean restrictedToWorkflows,
		List<String> selectedWorkflows
) {

	public RunnerGroupRequest {
		selectedRepositoryIds = selectedRepositoryIds == null ? null
				: List.copyOf(selectedRepositoryIds);
		selectedWorkflows = List.copyOf(selectedWorkflows);
	}

}
