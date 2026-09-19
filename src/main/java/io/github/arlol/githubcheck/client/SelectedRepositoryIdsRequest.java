package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * Body of the PUTs that replace a repository selection: the Actions policy's
 * and a runner group's.
 */
@GitHubEndpoint(
		request = { "PUT /orgs/{org}/actions/permissions/repositories",
				"PUT /orgs/{org}/actions/runner-groups/{runner_group_id}/repositories",
				"PUT /orgs/{org}/actions/secrets/{secret_name}/repositories",
				"PUT /orgs/{org}/actions/variables/{name}/repositories" }
)
public record SelectedRepositoryIdsRequest(
		List<Long> selectedRepositoryIds
) {

	public SelectedRepositoryIdsRequest {
		selectedRepositoryIds = List.copyOf(selectedRepositoryIds);
	}

}
