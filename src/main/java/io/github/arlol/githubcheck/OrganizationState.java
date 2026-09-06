package io.github.arlol.githubcheck;

import java.util.List;

import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualWebhook;
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
		List<ActualOrgSecret> actionSecrets,
		List<ActualOrgVariable> actionVariables,
		List<ActualWebhook> webhooks,
		List<ActualCustomProperty> customProperties,
		List<ActualRuleset> rulesets,
		List<ActualCodeSecurityConfiguration> codeSecurityConfigurations,
		List<ActualTeam> teams,
		List<ActualOrgMember> members,
		List<ActualRunnerGroup> runnerGroups
) {

	public OrganizationState {
		actionSecrets = List.copyOf(actionSecrets);
		actionVariables = List.copyOf(actionVariables);
		webhooks = List.copyOf(webhooks);
		customProperties = List.copyOf(customProperties);
		rulesets = List.copyOf(rulesets);
		codeSecurityConfigurations = List.copyOf(codeSecurityConfigurations);
		teams = List.copyOf(teams);
		members = List.copyOf(members);
		runnerGroups = List.copyOf(runnerGroups);
	}

	/** A state with nothing in the sections added after the first five. */
	public OrganizationState(
			String login,
			ActualOrganization settings,
			ActualOrgActionsPermissions actionsPermissions,
			ActualWorkflowPermissions workflowPermissions,
			List<ActualOrgSecret> actionSecrets
	) {
		this(
				login,
				settings,
				actionsPermissions,
				workflowPermissions,
				actionSecrets,
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of()
		);
	}

}
