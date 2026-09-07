package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.arlol.githubcheck.FetchFailures;
import io.github.arlol.githubcheck.OrganizationState;
import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSelectedActions;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * One account's entry in the exported file: an organization keyed under
 * {@code organizations}, or a personal account under {@code users}.
 * <p>
 * Every other exporter builds one section of {@code Organization}; this class
 * is the only one that knows the schema's own field order and turns a group the
 * token could not read into a note sitting where that section would have been.
 * Position is what tells a reader which section is missing rather than empty —
 * an absent keyed section would otherwise read as "delete all of these" to a
 * later {@code --fix} run.
 */
public final class AccountExporter {

	private static final String SECRET_VALUES_NOTE = "secret values are never returned by GitHub; supply them through DRIFTY_GITHUB_SECRETS";

	private AccountExporter() {
	}

	public static PklNode.Member organization(
			OrganizationState state,
			List<FetchFailures.Failure> failures,
			List<PklNode> repositories,
			SchemaDefaults defaults
	) {
		return new PklNode.Field(
				state.login(),
				new PklNode.Obj(
						organizationMembers(
								state,
								failures,
								repositories,
								defaults
						)
				)
		);
	}

	/** A personal account has no org-level settings, only its repositories. */
	public static PklNode.Member user(
			String login,
			List<PklNode> repositories
	) {
		List<PklNode.Member> members = new ArrayList<>();
		Fields.objects("repositories", repositories).ifPresent(members::add);
		return new PklNode.Field(login, new PklNode.Obj(members));
	}

	/**
	 * The entry's body without the keyed wrapper — a seam for a test that
	 * checks every schema field is covered without also parsing the rendered
	 * Pkl back apart.
	 */
	static List<PklNode.Member> organizationMembers(
			OrganizationState state,
			List<FetchFailures.Failure> failures,
			List<PklNode> repositories,
			SchemaDefaults defaults
	) {
		var members = new ArrayList<PklNode.Member>();

		if (state.settings() != null) {
			members.addAll(
					OrganizationExporter
							.settings(state.settings(), defaults.organization())
			);
		}
		addFailureNote(members, failures, "org_settings");

		if (state.actionsPermissions() != null) {
			Fields.nested(
					"actionsPermissions",
					actionsPermissionsMembers(
							state.actionsPermissions(),
							defaults
					)
			).ifPresent(members::add);
		}
		addFailureNote(members, failures, "org_actions_permissions");

		if (state.workflowPermissions() != null) {
			members.addAll(
					workflowPermissionsMembers(
							state.workflowPermissions(),
							defaults.organization()
					)
			);
		}
		addFailureNote(members, failures, "org_workflow_permissions");

		addActionsSecrets(members, state.actionSecrets(), defaults.orgSecret());
		addFailureNote(members, failures, "org_action_secrets");

		addActionsVariables(
				members,
				state.actionVariables(),
				defaults.orgVariable()
		);
		addFailureNote(members, failures, "org_action_variables");

		Fields.mapping(
				"webhooks",
				state.webhooks()
						.stream()
						.sorted(Comparator.comparing(ActualWebhook::url))
						.map(
								webhook -> WebhookExporter
										.entry(webhook, defaults.webhook())
						)
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_webhooks");

		Fields.mapping(
				"customProperties",
				state.customProperties()
						.stream()
						.sorted(
								Comparator.comparing(ActualCustomProperty::name)
						)
						.map(
								property -> CustomPropertyExporter.entry(
										property,
										defaults.customProperty()
								)
						)
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_custom_properties");

		Fields.mapping(
				"rulesets",
				state.rulesets()
						.stream()
						.sorted(Comparator.comparing(ActualRuleset::name))
						.map(
								ruleset -> RulesetExporter
										.entry(ruleset, defaults, true)
						)
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_rulesets");

		Fields.mapping(
				"codeSecurityConfigurations",
				state.codeSecurityConfigurations()
						.stream()
						.sorted(
								Comparator.comparing(
										ActualCodeSecurityConfiguration::name
								)
						)
						.map(
								configuration -> CodeSecurityConfigurationExporter
										.entry(configuration, defaults)
						)
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_code_security_configurations");

		Fields.mapping(
				"teams",
				state.teams()
						.stream()
						.sorted(Comparator.comparing(ActualTeam::slug))
						.map(team -> TeamExporter.entry(team, defaults.team()))
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_teams");

		Fields.mapping(
				"members",
				state.members()
						.stream()
						.sorted(Comparator.comparing(ActualOrgMember::login))
						.map(AccountExporter::memberEntry)
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_members");

		Fields.mapping(
				"runnerGroups",
				state.runnerGroups()
						.stream()
						.sorted(Comparator.comparing(ActualRunnerGroup::name))
						.map(
								runnerGroup -> RunnerGroupExporter.entry(
										runnerGroup,
										defaults.runnerGroup()
								)
						)
						.toList()
		).ifPresent(members::add);
		addFailureNote(members, failures, "org_runner_groups");

		Fields.objects("repositories", repositories).ifPresent(members::add);

		return List.copyOf(members);
	}

	private static PklNode.Member memberEntry(ActualOrgMember member) {
		return new PklNode.Field(
				member.login(),
				PklNode.Scalar.of(member.role())
		);
	}

	private static List<PklNode.Member> actionsPermissionsMembers(
			ActualOrgActionsPermissions actual,
			SchemaDefaults defaults
	) {
		Drifty.ActionsPermissions base = defaults.actionsPermissions();
		var members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"enabledRepositories",
								wire(actual.enabledRepositories()),
								base.enabledRepositories.toString()
						),
						Fields.field(
								"allowedActions",
								wire(actual.allowedActions()),
								base.allowedActions.toString()
						),
						Fields.field(
								"shaPinningRequired",
								actual.shaPinningRequired(),
								base.shaPinningRequired
						),
						Fields.strings(
								"selectedRepositories",
								actual.selectedRepositories(),
								base.selectedRepositories
						)
				)
		);
		if (actual.selectedActions() != null) {
			Fields.nested(
					"selectedActions",
					selectedActionsMembers(
							actual.selectedActions(),
							defaults.selectedActions()
					)
			).ifPresent(members::add);
		}
		return members;
	}

	private static List<PklNode.Member> selectedActionsMembers(
			ActualSelectedActions actual,
			Drifty.SelectedActions base
	) {
		return Fields.members(
				Fields.field(
						"githubOwnedAllowed",
						actual.githubOwnedAllowed(),
						base.githubOwnedAllowed
				),
				Fields.field(
						"verifiedAllowed",
						actual.verifiedAllowed(),
						base.verifiedAllowed
				),
				Fields.strings(
						"patternsAllowed",
						actual.patternsAllowed(),
						base.patternsAllowed
				)
		);
	}

	private static List<PklNode.Member> workflowPermissionsMembers(
			ActualWorkflowPermissions actual,
			Drifty.Organization base
	) {
		return Fields.members(
				Fields.field(
						"defaultWorkflowPermissions",
						wire(actual.defaultWorkflowPermissions()),
						base.defaultWorkflowPermissions.toString()
				),
				Fields.field(
						"canApprovePullRequestReviews",
						actual.canApprovePullRequestReviews(),
						base.canApprovePullRequestReviews
				)
		);
	}

	private static void addActionsSecrets(
			List<PklNode.Member> members,
			List<ActualOrgSecret> secrets,
			Drifty.OrgSecret base
	) {
		if (secrets.isEmpty()) {
			return;
		}
		var entries = new ArrayList<PklNode.Member>();
		secrets.stream()
				.sorted(Comparator.comparing(ActualOrgSecret::name))
				.forEach(secret -> entries.add(orgSecretEntry(secret, base)));
		entries.add(Fields.note(SECRET_VALUES_NOTE));
		Fields.mapping("actionsSecrets", entries).ifPresent(members::add);
	}

	private static PklNode.Member orgSecretEntry(
			ActualOrgSecret secret,
			Drifty.OrgSecret base
	) {
		List<PklNode.Member> fields = Fields.members(
				Fields.field(
						"visibility",
						wire(secret.visibility()),
						base.visibility.toString()
				),
				Fields.strings(
						"selectedRepositories",
						secret.selectedRepositories(),
						base.selectedRepositories
				)
		);
		return new PklNode.Field(secret.name(), new PklNode.Obj(fields));
	}

	private static void addActionsVariables(
			List<PklNode.Member> members,
			List<ActualOrgVariable> variables,
			Drifty.OrgVariable base
	) {
		Fields.mapping(
				"actionsVariables",
				variables.stream()
						.sorted(Comparator.comparing(ActualOrgVariable::name))
						.map(variable -> orgVariableEntry(variable, base))
						.toList()
		).ifPresent(members::add);
	}

	private static PklNode.Member orgVariableEntry(
			ActualOrgVariable variable,
			Drifty.OrgVariable base
	) {
		List<PklNode.Member> fields = Fields.members(
				Fields.required("value", variable.value()),
				Fields.field(
						"visibility",
						wire(variable.visibility()),
						base.visibility.toString()
				),
				Fields.strings(
						"selectedRepositories",
						variable.selectedRepositories(),
						base.selectedRepositories
				)
		);
		return new PklNode.Field(variable.name(), new PklNode.Obj(fields));
	}

	/**
	 * Package-visible rather than {@code private}: {@link RepositoryExporter}
	 * places a repository's own failure notes the same way, so the lookup lives
	 * once instead of twice.
	 */
	static void addFailureNote(
			List<PklNode.Member> members,
			List<FetchFailures.Failure> failures,
			String group
	) {
		for (FetchFailures.Failure failure : failures) {
			if (failure.group().equals(group)) {
				members.add(
						Fields.note(failure.group() + ": " + failure.reason())
				);
			}
		}
	}

	/**
	 * These client enums carry GitHub's wire values only in their
	 * {@code @JsonProperty} annotations, unlike a {@code Drifty.*} enum
	 * generated from a Pkl union, whose {@code toString} already spells them —
	 * see {@link PklNode.Scalar#of(Enum)}. Comparing against the schema's
	 * default therefore goes through an explicit switch rather than
	 * {@code toString()}, the same way {@code PklTypes} converts the other
	 * direction.
	 */
	private static String wire(ActionsEnabledRepositories value) {
		return switch (value) {
		case ALL -> "all";
		case NONE -> "none";
		case SELECTED -> "selected";
		};
	}

	private static String wire(AllowedActions value) {
		return switch (value) {
		case ALL -> "all";
		case LOCAL_ONLY -> "local_only";
		case SELECTED -> "selected";
		};
	}

	private static String wire(SecretVisibility value) {
		return switch (value) {
		case ALL -> "all";
		case PRIVATE -> "private";
		case SELECTED -> "selected";
		};
	}

	/**
	 * Package-visible rather than {@code private}: {@code RepositoryExporter}
	 * compares the same client enum against the same schema union
	 * ({@code defaultWorkflowPermissions} exists on both {@code Organization}
	 * and {@code Repository}), so this is the one place the translation lives
	 * rather than a second copy that could drift from this one if the enum ever
	 * grew a third value.
	 */
	static String wire(DefaultWorkflowPermissions value) {
		return switch (value) {
		case READ -> "read";
		case WRITE -> "write";
		};
	}

}
