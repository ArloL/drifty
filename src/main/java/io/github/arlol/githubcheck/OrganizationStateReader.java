package io.github.arlol.githubcheck;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.CodeSecurityDefaultResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrgSecretResponse;
import io.github.arlol.githubcheck.client.OrgVariableResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RulesetSourceType;
import io.github.arlol.githubcheck.client.RunnerGroupResponse;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.client.SelectedActions;
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.client.TeamResponse;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Reads one organization's actual state from GitHub. The org-level counterpart
 * of {@link RepositoryStateReader}, split from {@link OrganizationChecker} for
 * the same reason: an export reads without comparing.
 */
public final class OrganizationStateReader {

	private final GitHubClient client;
	private final FetchFailures failures;

	public OrganizationStateReader(
			GitHubClient client,
			FetchFailures failures
	) {
		this.client = client;
		this.failures = failures;
	}

	/**
	 * One organization's actual state, with every request that can be in flight
	 * at once in flight at once.
	 * <p>
	 * Three levels, one more than {@code RepositoryStateReader.fetchState}
	 * needs, and the extra one is {@code GET /orgs/{org}}: it is how this
	 * learns the organization exists — a 404 there is what makes the entry
	 * {@code MISSING} — so every group below it waits on it rather than firing
	 * forty requests at a login GitHub has never heard of. Below that, each
	 * group's listing starts at once and each read that names something a
	 * listing returned waits one round trip: a ruleset's rules, a team's two
	 * member listings, a code security configuration's repositories, the
	 * repositories behind a {@code selected} secret, variable or runner group,
	 * and the allow-list and repository selection behind {@code selected}
	 * Actions permissions.
	 * <p>
	 * It used to send all of them one after another, which for an organization
	 * with a few teams and rulesets is forty round trips before the first
	 * repository is looked at — {@code GitHubCheck.check} runs an organization
	 * before its repositories, so that time is the head of the whole run.
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

		try (var fanout = new Fanout<>(failures, managed)) {
			Supplier<@Nullable ActualOrgActionsPermissions> permissions = fanout
					.read(
							Drifty.OrgGroupName.ORG_ACTIONS_PERMISSIONS,
							() -> actionsPermissions(fanout, login),
							null
					);

			Supplier<@Nullable ActualWorkflowPermissions> workflowPermissions = fanout
					.read(
							Drifty.OrgGroupName.ORG_WORKFLOW_PERMISSIONS,
							() -> ActualTypes.workflowPermissions(
									client.getOrgWorkflowPermissions(login)
							),
							null
					);

			Supplier<List<ActualOrgSecret>> secrets = fanout.read(
					Drifty.OrgGroupName.ORG_ACTION_SECRETS,
					() -> orgSecrets(fanout, login),
					List.of()
			);

			Supplier<List<ActualOrgVariable>> variables = fanout.read(
					Drifty.OrgGroupName.ORG_ACTION_VARIABLES,
					() -> orgVariables(fanout, login),
					List.of()
			);

			Supplier<List<ActualWebhook>> webhooks = fanout.read(
					Drifty.OrgGroupName.ORG_WEBHOOKS,
					() -> client.getOrgWebhooks(login)
							.stream()
							.map(ActualTypes::webhook)
							.toList(),
					List.of()
			);

			Supplier<List<ActualCustomProperty>> customProperties = fanout.read(
					Drifty.OrgGroupName.ORG_CUSTOM_PROPERTIES,
					() -> customProperties(login),
					List.of()
			);

			Supplier<List<ActualRuleset>> rulesets = fanout.read(
					Drifty.OrgGroupName.ORG_RULESETS,
					() -> orgRulesets(fanout, login),
					List.of()
			);

			Supplier<List<ActualCodeSecurityConfiguration>> codeSecurityConfigurations = fanout
					.read(
							Drifty.OrgGroupName.ORG_CODE_SECURITY_CONFIGURATIONS,
							() -> codeSecurityConfigurations(fanout, login),
							List.of()
					);

			Supplier<List<ActualTeam>> teams = fanout.read(
					Drifty.OrgGroupName.ORG_TEAMS,
					() -> teams(fanout, login),
					List.of()
			);

			// Two listings, one per role, neither waiting on the other.
			Supplier<List<ActualOrgMember>> members = fanout.read(
					Drifty.OrgGroupName.ORG_MEMBERS,
					() -> orgMembers(fanout, login),
					List.of()
			);

			Supplier<List<ActualRunnerGroup>> runnerGroups = fanout.read(
					Drifty.OrgGroupName.ORG_RUNNER_GROUPS,
					() -> runnerGroups(fanout, login),
					List.of()
			);

			return new OrganizationState(
					login,
					settings,
					permissions.get(),
					workflowPermissions.get(),
					secrets.get(),
					variables.get(),
					webhooks.get(),
					customProperties.get(),
					rulesets.get(),
					codeSecurityConfigurations.get(),
					teams.get(),
					members.get(),
					runnerGroups.get()
			);
		}
	}

	/**
	 * The allow-list and the repository selection only exist in
	 * {@code selected} mode — asking for either in any other mode is a 404 — so
	 * both wait on the response that says which mode it is, and then go out
	 * together.
	 */
	private ActualOrgActionsPermissions actionsPermissions(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		var response = client.getOrgActionsPermissions(login);
		Supplier<SelectedActions> selected = response
				.allowedActions() == AllowedActions.SELECTED ? fanout
						.start(() -> client.getOrgSelectedActions(login))
						: () -> null;
		Supplier<List<String>> selectedRepositories = response
				.enabledRepositories() == ActionsEnabledRepositories.SELECTED
						? fanout.start(
								() -> client
										.getOrgActionsPermissionsRepositories(
												login
										)
										.stream()
										.map(RepositorySummaryResponse::name)
										.toList()
						)
						: List::of;
		return ActualTypes.orgActionsPermissions(
				response,
				selected.get(),
				selectedRepositories.get()
		);
	}

	/** One listing per role, both in flight together. */
	private List<ActualOrgMember> orgMembers(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		Supplier<List<SimpleUser>> admins = fanout
				.start(() -> client.listOrgMembers(login, "admin"));
		Supplier<List<SimpleUser>> members = fanout
				.start(() -> client.listOrgMembers(login, "member"));
		return ActualTypes.orgMembers(admins.get(), members.get());
	}

	/**
	 * The repositories of a group cost one request each and only exist under
	 * {@code selected} visibility, so they are read for those groups alone —
	 * and, once the listing has answered, all at once.
	 */
	private List<ActualRunnerGroup> runnerGroups(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		return client.listRunnerGroups(login)
				.stream()
				.map(
						group -> Map.entry(
								group,
								runnerGroupRepositories(fanout, login, group)
						)
				)
				.toList()
				.stream()
				.map(
						entry -> ActualTypes.runnerGroup(
								entry.getKey(),
								entry.getValue().get()
						)
				)
				.toList();
	}

	private Supplier<List<String>> runnerGroupRepositories(
			Fanout<Drifty.OrgGroupName> fanout,
			String login,
			RunnerGroupResponse group
	) {
		if (!"selected".equals(group.visibility())) {
			return List::of;
		}
		return fanout.start(
				() -> names(
						client.getRunnerGroupRepositories(login, group.id())
				)
		);
	}

	/**
	 * Two member listings per team after the one that lists the teams, one per
	 * role, all of them in flight together. Enterprise teams are not the
	 * organization's to change and are dropped.
	 */
	private List<ActualTeam> teams(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		return client.listOrgTeams(login)
				.stream()
				.filter(t -> !"enterprise".equals(t.type()))
				.map(
						team -> new PendingTeam(
								team,
								fanout.start(
										() -> client.getTeamMembers(
												login,
												team.slug(),
												"member"
										)
								),
								fanout.start(
										() -> client.getTeamMembers(
												login,
												team.slug(),
												"maintainer"
										)
								)
						)
				)
				.toList()
				.stream()
				.map(
						pending -> ActualTypes.team(
								pending.team(),
								pending.members().get(),
								pending.maintainers().get()
						)
				)
				.toList();
	}

	/** One team's two member listings, already sent. */
	private record PendingTeam(
			TeamResponse team,
			Supplier<List<SimpleUser>> members,
			Supplier<List<SimpleUser>> maintainers
	) {
	}

	/**
	 * The organization's own configurations — GitHub's global ones are not its
	 * to change — with the defaults listing and each configuration's attached
	 * repositories read together once the configuration listing answers.
	 * <p>
	 * The defaults listing does not start before it: it is wanted only if there
	 * is a configuration to attribute one to, and an organization with none
	 * would otherwise spend a request finding that out — and, on a token that
	 * cannot read it, fail the group it had nothing to say about.
	 */
	private List<ActualCodeSecurityConfiguration> codeSecurityConfigurations(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		var configurations = client.getCodeSecurityConfigurations(login)
				.stream()
				.filter(c -> "organization".equals(c.targetType()))
				.toList();
		if (configurations.isEmpty()) {
			return List.of();
		}
		Supplier<List<CodeSecurityDefaultResponse>> pendingDefaults = fanout
				.start(() -> client.getCodeSecurityDefaults(login));
		var pending = configurations.stream()
				.map(
						c -> Map.entry(
								c,
								fanout.start(
										() -> client
												.getCodeSecurityConfigurationRepositories(
														login,
														c.id()
												)
								)
						)
				)
				.toList();
		Map<Long, String> defaults = new HashMap<>();
		for (CodeSecurityDefaultResponse d : pendingDefaults.get()) {
			if (d.configuration() != null) {
				defaults.put(d.configuration().id(), d.defaultForNewRepos());
			}
		}
		return pending.stream()
				.map(
						entry -> ActualTypes.codeSecurityConfiguration(
								entry.getKey(),
								defaults.get(entry.getKey().id()),
								entry.getValue().get()
						)
				)
				.toList();
	}

	/**
	 * One request per ruleset after the listing, as on the repository side: the
	 * listing carries no rules or conditions. Enterprise rulesets arrive in the
	 * listing and are dropped — the organization cannot change them.
	 */
	private List<ActualRuleset> orgRulesets(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		return client.listOrgRulesets(login)
				.stream()
				.filter(rs -> rs.sourceType() != RulesetSourceType.ENTERPRISE)
				.map(
						rs -> fanout.start(
								() -> client.getOrgRuleset(login, rs.id())
						)
				)
				.toList()
				.stream()
				.map(Supplier::get)
				.map(ActualTypes::ruleset)
				.toList();
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

	private List<ActualOrgVariable> orgVariables(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		return client.getOrgActionVariables(login)
				.stream()
				.map(
						variable -> Map.entry(
								variable,
								variableRepositories(fanout, login, variable)
						)
				)
				.toList()
				.stream()
				.map(
						entry -> ActualTypes.orgVariable(
								entry.getKey(),
								entry.getValue().get()
						)
				)
				.toList();
	}

	/**
	 * The repository names behind a {@code selected} variable, read the way
	 * {@link #secretRepositories} reads a secret's.
	 */
	private Supplier<List<String>> variableRepositories(
			Fanout<Drifty.OrgGroupName> fanout,
			String login,
			OrgVariableResponse variable
	) {
		if (variable.visibility() != SecretVisibility.SELECTED) {
			return List::of;
		}
		return fanout.start(
				() -> names(
						client.getOrgActionVariableRepositories(
								login,
								variable.name()
						)
				)
		);
	}

	private List<ActualOrgSecret> orgSecrets(
			Fanout<Drifty.OrgGroupName> fanout,
			String login
	) {
		return client.getOrgActionSecrets(login)
				.stream()
				.map(
						secret -> Map.entry(
								secret,
								secretRepositories(fanout, login, secret)
						)
				)
				.toList()
				.stream()
				.map(
						entry -> ActualTypes.orgSecret(
								entry.getKey(),
								entry.getValue().get()
						)
				)
				.toList();
	}

	/**
	 * The repository names behind a {@code selected} secret cost one request
	 * each, so they are read only for the secrets that have them — the other
	 * visibilities name no repositories at all.
	 */
	private Supplier<List<String>> secretRepositories(
			Fanout<Drifty.OrgGroupName> fanout,
			String login,
			OrgSecretResponse secret
	) {
		if (secret.visibility() != SecretVisibility.SELECTED) {
			return List::of;
		}
		return fanout.start(
				() -> names(
						client.getOrgActionSecretRepositories(
								login,
								secret.name()
						)
				)
		);
	}

	private static List<String> names(
			List<RepositorySummaryResponse> repositories
	) {
		return repositories.stream()
				.map(RepositorySummaryResponse::name)
				.toList();
	}

	/**
	 * The groups {@link #fetchState} could not read, for the exporter to note.
	 */
	List<FetchFailures.Failure> fetchFailures() {
		return failures.failures();
	}

}
