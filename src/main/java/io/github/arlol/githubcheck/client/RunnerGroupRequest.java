package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the runner group POST and PATCH. The repository ids are only accepted
 * by the POST; the PATCH has its own repositories endpoint, so a PATCH body
 * leaves them null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
