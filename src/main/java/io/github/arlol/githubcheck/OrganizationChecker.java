package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftReport;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.drift.OrgActionSecretsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgActionVariablesDriftGroup;
import io.github.arlol.githubcheck.drift.OrgActionsPermissionsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgCodeSecurityConfigurationsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgCustomPropertiesDriftGroup;
import io.github.arlol.githubcheck.drift.OrgMembersDriftGroup;
import io.github.arlol.githubcheck.drift.OrgRulesetDriftGroup;
import io.github.arlol.githubcheck.drift.OrgRunnerGroupsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgSettingsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgTeamsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgWebhooksDriftGroup;
import io.github.arlol.githubcheck.drift.OrgWorkflowPermissionsDriftGroup;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Checks one organization: compares its actual state against {@code drifty.pkl}
 * and — in fix mode — writes the drift away. Reading that state is
 * {@link OrganizationStateReader}'s job. The org-level counterpart of
 * {@link RepositoryChecker}.
 */
public class OrganizationChecker {

	private final GitHubClient client;
	private final OrganizationStateReader reader;
	private final boolean fix;
	// Both belong to the org secrets group: a secret value comes from
	// DRIFTY_GITHUB_SECRETS, and the state file is what says which value drifty
	// last wrote, since GitHub never reads one back.
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

	OrganizationChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this.client = client;
		this.reader = new OrganizationStateReader(client, FetchFailures.STRICT);
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
	}

	/**
	 * Checks one organization against the block the config keys under its
	 * login.
	 *
	 * @param repos the organization's repositories as GitHub listed them. The
	 *              caller already has that listing, and the org secrets group
	 *              needs it to turn a secret's selected repositories into names
	 */
	public CheckResult.Entry check(
			String login,
			Drifty.Organization desired,
			List<RepositorySummaryResponse> repos
	) {
		ManagedGroups<Drifty.OrgGroupName> managed = ManagedGroups
				.of(desired.managed);
		try {
			OrganizationState actual = reader.fetchState(login, managed);
			if (actual.settings() == null) {
				return CheckResult.Entry.missing(login);
			}
			return DriftReport.entry(
					login,
					managed,
					computeGroupDrifts(actual, desired, repositoryIds(repos)),
					fix
			);
		} catch (GitHubApiException e) {
			return CheckResult.Entry.error(login, e.getMessage());
		}
	}

	private static Map<String, Long> repositoryIds(
			List<RepositorySummaryResponse> repos
	) {
		return repos.stream()
				.filter(repo -> repo.id() != null)
				.collect(
						Collectors.toMap(
								RepositorySummaryResponse::name,
								RepositorySummaryResponse::id,
								(a, _) -> a
						)
				);
	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	/**
	 * This organization's drifted groups — see {@link DriftReport#groupDrifts}.
	 */
	Map<DriftGroup<Drifty.OrgGroupName>, List<DriftFix>> computeGroupDrifts(
			OrganizationState actual,
			Drifty.Organization desired,
			Map<String, Long> repositoryIds
	) {
		return DriftReport
				.groupDrifts(createDriftGroups(actual, desired, repositoryIds));
	}

	List<DriftGroup<Drifty.OrgGroupName>> createDriftGroups(
			OrganizationState actual,
			Drifty.Organization desired,
			Map<String, Long> repositoryIds
	) {
		var groups = new ArrayList<DriftGroup<Drifty.OrgGroupName>>();
		groups.add(
				new OrgSettingsDriftGroup(
						desired,
						actual.settings(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgActionsPermissionsDriftGroup(
						desired.actionsPermissions,
						actual.actionsPermissions(),
						repositoryIds,
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgWorkflowPermissionsDriftGroup(
						desired.defaultWorkflowPermissions,
						desired.canApprovePullRequestReviews,
						actual.workflowPermissions(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgActionSecretsDriftGroup(
						desired.actionsSecrets,
						actual.actionSecrets(),
						repositoryIds,
						githubSecrets,
						state,
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgActionVariablesDriftGroup(
						desired.actionsVariables,
						actual.actionVariables(),
						repositoryIds,
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgWebhooksDriftGroup(
						desired.webhooks,
						actual.webhooks(),
						githubSecrets,
						state,
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgCustomPropertiesDriftGroup(
						desired.customProperties,
						actual.customProperties(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgRulesetDriftGroup(
						desired.rulesets,
						actual.rulesets(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgCodeSecurityConfigurationsDriftGroup(
						desired.codeSecurityConfigurations,
						actual.codeSecurityConfigurations(),
						repositoryIds,
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgTeamsDriftGroup(
						desired.teams,
						actual.teams(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgMembersDriftGroup(
						desired.members,
						actual.members(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgRunnerGroupsDriftGroup(
						desired.runnerGroups,
						actual.runnerGroups(),
						repositoryIds,
						client,
						actual.login()
				)
		);
		ManagedGroups<Drifty.OrgGroupName> managed = ManagedGroups
				.of(desired.managed);
		// One filter over the finished list, so a group added later is filtered
		// without its author having to know this feature exists.
		return groups.stream()
				.filter(group -> managed.manages(group.name()))
				.toList();
	}

}
