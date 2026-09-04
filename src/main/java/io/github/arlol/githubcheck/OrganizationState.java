package io.github.arlol.githubcheck;

import java.util.List;

import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;

/**
 * Everything drifty knows about one organization on GitHub, in drifty's own
 * vocabulary — the org-level counterpart of {@link RepositoryState}, and held
 * to the same rule: no field here is a GitHub response type.
 * <p>
 * A field is null when the organization does not manage the group that reads
 * it: the request is never sent, and the group that would compare it is not
 * built.
 */
public record OrganizationState(
		String login,
		ActualOrganization settings,
		ActualOrgActionsPermissions actionsPermissions,
		ActualWorkflowPermissions workflowPermissions,
		List<ActualOrgSecret> actionSecrets
) {

	public OrganizationState {
		actionSecrets = List.copyOf(actionSecrets);
	}

}
