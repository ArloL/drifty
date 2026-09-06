package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.CodeSecurityDefaultResponse;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrgSecretResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RulesetSourceType;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftFixer;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
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
 * Checks one organization: fetches its actual state, compares it against
 * {@code drifty.pkl}, and — in fix mode — writes the drift away. The org-level
 * counterpart of {@link RepositoryChecker}.
 */
public class OrganizationChecker {

	private final GitHubClient client;
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
		List<String> unmanaged = managed.unmanaged()
				.stream()
				.map(Drifty.OrgGroupName::toString)
				.toList();
		try {
			OrganizationState actual = fetchState(login, managed);
			if (actual.settings() == null) {
				return CheckResult.Entry.missing(login);
			}
			var groupDrifts = computeGroupDrifts(
					actual,
					desired,
					repositoryIds(repos)
			);

			if (fix) {
				var outcome = DriftFixer.applyFixes(groupDrifts);
				return CheckResult.Entry.fixed(
						login,
						DriftFixer.render(outcome.unfixedItems()),
						DriftFixer.fixReports(outcome)
				);
			}

			List<String> diffs = groupDrifts.values()
					.stream()
					.flatMap(List::stream)
					.flatMap(driftFix -> driftFix.items().stream())
					.map(DriftItem::message)
					.toList();
			if (diffs.isEmpty()) {
				return CheckResult.Entry.ok(login, unmanaged);
			}
			// In check mode, preview which groups --fix would act on.
			return CheckResult.Entry.drift(
					login,
					diffs,
					groupDrifts.keySet()
							.stream()
							.map(group -> group.name().toString())
							.toList(),
					unmanaged
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

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	/**
	 * Reads the organization state, one request per managed group.
	 * <p>
	 * {@code GET /orgs/{org}} is sent even when {@code org_settings} is
	 * unmanaged: it is how drifty learns the organization exists, and any
	 * member can read it. Every other request here belongs to a group and is
	 * skipped with it — filtering a group out of the comparison alone would
	 * still send its request, and an organization someone else administers is
	 * where those return 403.
	 * <p>
	 * When that first read 404s there is nothing to read the rest of: sending
	 * the group requests anyway would turn "this organization does not exist"
	 * into whichever error the next endpoint answered with, so the state comes
	 * back with a null {@code settings} and {@link #check} reports it missing.
	 */
	OrganizationState fetchState(
			String login,
			ManagedGroups<Drifty.OrgGroupName> managed
	) {
		var organization = client.getOrganization(login);
		if (organization.isEmpty()) {
			return new OrganizationState(login, null, null, null, List.of());
		}
		var settings = ActualTypes.organization(organization.orElseThrow());

		ActualOrgActionsPermissions permissions = null;
		if (managed.manages(Drifty.OrgGroupName.ORG_ACTIONS_PERMISSIONS)) {
			var response = client.getOrgActionsPermissions(login);
			// The allow-list only exists in "selected" mode; asking for it in
			// any other mode is a 404.
			var selected = response.allowedActions() == AllowedActions.SELECTED
					? client.getOrgSelectedActions(login)
					: null;
			// Same for the repository selection: it is only there under
			// "selected".
			List<String> selectedRepositories = response
					.enabledRepositories() == ActionsEnabledRepositories.SELECTED
							? client.getOrgActionsPermissionsRepositories(login)
									.stream()
									.map(RepositorySummaryResponse::name)
									.toList()
							: List.of();
			permissions = ActualTypes.orgActionsPermissions(
					response,
					selected,
					selectedRepositories
			);
		}

		var workflowPermissions = managed
				.manages(Drifty.OrgGroupName.ORG_WORKFLOW_PERMISSIONS)
						? ActualTypes.workflowPermissions(
								client.getOrgWorkflowPermissions(login)
						)
						: null;

		List<ActualOrgSecret> secrets = managed
				.manages(Drifty.OrgGroupName.ORG_ACTION_SECRETS)
						? orgSecrets(login)
						: List.of();

		List<ActualOrgVariable> variables = managed
				.manages(Drifty.OrgGroupName.ORG_ACTION_VARIABLES)
						? orgVariables(login)
						: List.of();

		List<ActualWebhook> webhooks = managed
				.manages(Drifty.OrgGroupName.ORG_WEBHOOKS)
						? client.getOrgWebhooks(login)
								.stream()
								.map(ActualTypes::webhook)
								.toList()
						: List.of();

		List<ActualCustomProperty> customProperties = managed
				.manages(Drifty.OrgGroupName.ORG_CUSTOM_PROPERTIES)
						? customProperties(login)
						: List.of();

		List<ActualRuleset> rulesets = managed
				.manages(Drifty.OrgGroupName.ORG_RULESETS) ? orgRulesets(login)
						: List.of();

		List<ActualCodeSecurityConfiguration> codeSecurityConfigurations = managed
				.manages(Drifty.OrgGroupName.ORG_CODE_SECURITY_CONFIGURATIONS)
						? codeSecurityConfigurations(login)
						: List.of();

		List<ActualTeam> teams = managed.manages(Drifty.OrgGroupName.ORG_TEAMS)
				? teams(login)
				: List.of();

		List<ActualOrgMember> members = managed
				.manages(Drifty.OrgGroupName.ORG_MEMBERS)
						? ActualTypes.orgMembers(
								client.listOrgMembers(login, "admin"),
								client.listOrgMembers(login, "member")
						)
						: List.of();

		List<ActualRunnerGroup> runnerGroups = managed
				.manages(Drifty.OrgGroupName.ORG_RUNNER_GROUPS)
						? runnerGroups(login)
						: List.of();

		return new OrganizationState(
				login,
				settings,
				permissions,
				workflowPermissions,
				secrets,
				variables,
				webhooks,
				customProperties,
				rulesets,
				codeSecurityConfigurations,
				teams,
				members,
				runnerGroups
		);
	}

	/**
	 * The repositories of a group cost one request each and only exist under
	 * {@code selected} visibility, so they are read for those groups alone.
	 */
	private List<ActualRunnerGroup> runnerGroups(String login) {
		return client.listRunnerGroups(login)
				.stream()
				.map(
						g -> ActualTypes.runnerGroup(
								g,
								"selected".equals(g.visibility()) ? client
										.getRunnerGroupRepositories(
												login,
												g.id()
										)
										.stream()
										.map(RepositorySummaryResponse::name)
										.toList() : List.of()
						)
				)
				.toList();
	}

	/**
	 * Two member listings per team after the one that lists the teams, one per
	 * role. Enterprise teams are not the organization's to change and are
	 * dropped.
	 */
	private List<ActualTeam> teams(String login) {
		return client.listOrgTeams(login)
				.stream()
				.filter(t -> !"enterprise".equals(t.type()))
				.map(
						t -> ActualTypes.team(
								t,
								client.getTeamMembers(
										login,
										t.slug(),
										"member"
								),
								client.getTeamMembers(
										login,
										t.slug(),
										"maintainer"
								)
						)
				)
				.toList();
	}

	/**
	 * The organization's own configurations — GitHub's global ones are not its
	 * to change — with the defaults listing read once and the attached
	 * repositories once per configuration.
	 */
	private List<ActualCodeSecurityConfiguration> codeSecurityConfigurations(
			String login
	) {
		var configurations = client.getCodeSecurityConfigurations(login)
				.stream()
				.filter(c -> "organization".equals(c.targetType()))
				.toList();
		if (configurations.isEmpty()) {
			return List.of();
		}
		Map<Long, String> defaults = new java.util.HashMap<>();
		for (CodeSecurityDefaultResponse d : client
				.getCodeSecurityDefaults(login)) {
			if (d.configuration() != null) {
				defaults.put(d.configuration().id(), d.defaultForNewRepos());
			}
		}
		return configurations.stream()
				.map(
						c -> ActualTypes.codeSecurityConfiguration(
								c,
								defaults.get(c.id()),
								client.getCodeSecurityConfigurationRepositories(
										login,
										c.id()
								)
						)
				)
				.toList();
	}

	/**
	 * One request per ruleset after the listing, as on the repository side: the
	 * listing carries no rules or conditions. Enterprise rulesets arrive in the
	 * listing and are dropped — the organization cannot change them.
	 */
	private List<ActualRuleset> orgRulesets(String login) {
		var rulesets = new ArrayList<ActualRuleset>();
		for (var rs : client.listOrgRulesets(login)) {
			if (rs.sourceType() == RulesetSourceType.ENTERPRISE) {
				continue;
			}
			rulesets.add(
					ActualTypes.ruleset(client.getOrgRuleset(login, rs.id()))
			);
		}
		return rulesets;
	}

	/**
	 * The organization's own definitions. An enterprise-owned one is not the
	 * organization's to change, so it is dropped here rather than reported as
	 * extra with a fix that always fails.
	 */
	private List<ActualCustomProperty> customProperties(String login) {
		return client.getOrgCustomProperties(login)
				.stream()
				.filter(p -> !"enterprise".equals(p.sourceType()))
				.map(ActualTypes::customProperty)
				.toList();
	}

	private List<ActualOrgVariable> orgVariables(String login) {
		return client.getOrgActionVariables(login)
				.stream()
				.map(
						variable -> ActualTypes.orgVariable(
								variable,
								variable.visibility() == SecretVisibility.SELECTED
										? client.getOrgActionVariableRepositories(
												login,
												variable.name()
										)
												.stream()
												.map(
														RepositorySummaryResponse::name
												)
												.toList()
										: List.of()
						)
				)
				.toList();
	}

	private List<ActualOrgSecret> orgSecrets(String login) {
		return client.getOrgActionSecrets(login)
				.stream()
				.map(
						secret -> ActualTypes.orgSecret(
								secret,
								secretRepositories(login, secret)
						)
				)
				.toList();
	}

	/**
	 * The repository names behind a {@code selected} secret cost one request
	 * each, so they are read only for the secrets that have them — the other
	 * visibilities name no repositories at all.
	 */
	private List<String> secretRepositories(
			String login,
			OrgSecretResponse secret
	) {
		if (secret.visibility() != SecretVisibility.SELECTED) {
			return List.of();
		}
		return client.getOrgActionSecretRepositories(login, secret.name())
				.stream()
				.map(RepositorySummaryResponse::name)
				.toList();
	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	/**
	 * The groups that drifted, with their fixes. A group is in only when it
	 * reported a drifted item — see
	 * {@link RepositoryChecker#computeGroupDrifts}, which the
	 * {@code Would fix:} preview named three groups on an organization that had
	 * drifted in one.
	 */
	Map<DriftGroup<Drifty.OrgGroupName>, List<DriftFix>> computeGroupDrifts(
			OrganizationState actual,
			Drifty.Organization desired,
			Map<String, Long> repositoryIds
	) {
		Map<DriftGroup<Drifty.OrgGroupName>, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
		for (var group : createDriftGroups(actual, desired, repositoryIds)) {
			var fixes = group.detect();
			if (fixes.stream().anyMatch(fix -> !fix.items().isEmpty())) {
				groupDrifts.put(group, fixes);
			}
		}
		return groupDrifts;
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
