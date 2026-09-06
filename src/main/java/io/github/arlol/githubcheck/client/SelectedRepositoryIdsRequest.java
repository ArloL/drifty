package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * Body of the PUTs that replace a repository selection: the Actions policy's
 * and a runner group's.
 */
public record SelectedRepositoryIdsRequest(
		List<Long> selectedRepositoryIds
) {

	public SelectedRepositoryIdsRequest {
		selectedRepositoryIds = List.copyOf(selectedRepositoryIds);
	}

}
