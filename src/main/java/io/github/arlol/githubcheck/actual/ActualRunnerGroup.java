package io.github.arlol.githubcheck.actual;

import java.util.List;
import java.util.Set;

/**
 * A self-hosted runner group on an organization. The selected repositories are
 * read only under {@code selected} visibility and are names, resolved from the
 * listing the checker already has; the default group is the one GitHub created
 * and never deletes.
 */
public record ActualRunnerGroup(
		long id,
		String name,
		String visibility,
		boolean isDefault,
		boolean allowsPublicRepositories,
		boolean restrictedToWorkflows,
		Set<String> selectedWorkflows,
		List<String> selectedRepositories
) {

	public ActualRunnerGroup {
		selectedWorkflows = Set.copyOf(selectedWorkflows);
		selectedRepositories = List.copyOf(selectedRepositories);
	}

}
