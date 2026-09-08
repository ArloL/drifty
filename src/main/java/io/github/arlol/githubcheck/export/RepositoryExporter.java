package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.arlol.githubcheck.FetchFailures;
import io.github.arlol.githubcheck.RepositoryState;
import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * One repository's own settings, as a {@code new { … }} element of the
 * {@code repositories} listing.
 * <p>
 * {@code visibility} carries both a field and a note, for the reason
 * {@code OrganizationExporter}'s ten check-only settings do: {@code
 * RepoSettingsDriftGroup} compares it but never writes it, so leaving out the
 * field would carry the schema's default forever while GitHub carries the real
 * value — a config that started at zero drift would drift on the very next run,
 * with no {@code --fix} able to clear it. The field closes that gap; the note
 * tells a developer drifty will never act on it.
 * <p>
 * {@code allowForking} is exported only when
 * {@link ActualRepository#organizationOwned()}: {@code RepoSettingsDriftGroup}
 * only compares it there too, because GitHub exposes the setting solely on an
 * organization-owned repository, and an export must not invent a comparison the
 * checker itself skips.
 * <p>
 * An archived repository skips the whole security block below
 * {@code mergeCommitMessage} in favour of one note. Its five endpoint-fed
 * booleans (vulnerability alerts, automated fixes, immutable releases, private
 * vulnerability reporting, code scanning default setup) read false because
 * {@code RepositoryChecker} does not fetch them for an archived repository at
 * all — not because they are off. Its {@code security_and_analysis} toggles and
 * workflow permissions, by contrast, are fetched regardless of archived state;
 * they are folded into the same guard because once a config sets
 * {@code archived = true}, {@code RepositoryChecker.createDriftGroups} runs
 * only {@code ArchivedDriftGroup} and skips every other group, so none of these
 * settings would ever be compared against this file again — exporting them
 * would add fields nothing acts on.
 * <p>
 * {@code failures} is not skipped along with those members, though: rulesets
 * and pages are the only two groups {@code fetchState} itself stops fetching
 * once a repository is archived (alongside the five security-flag endpoints
 * above) — branch protections, secrets, variables, environments, webhooks,
 * custom properties, collaborators and workflow permissions are all still
 * fetched and can still 403. Those failures are rendered as bare notes rather
 * than positioned beside the member they would otherwise sit next to, since
 * that member is never rendered here at all — and they still reach
 * {@code managed} through {@link AccountExporter#addUnmanagedGroups}, which
 * runs before the archived branch for that reason.
 */
public final class RepositoryExporter {

	private static final String VISIBILITY_NOT_WRITABLE = "drifty reports it but never changes it: public to private breaks forks, private to public exposes code";

	private static final String ARCHIVED_NOTE = "drifty checks only archived on an archived repository, so its other settings are neither compared nor exported";

	private static final String ACTIONS_SECRET_VALUES_NOTE = "secret values are never returned by GitHub; supply them through DRIFTY_GITHUB_SECRETS";

	private RepositoryExporter() {
	}

	/**
	 * @param failures the groups this repository's own fetch could not read —
	 *                 see {@code RepositoryChecker.fetchFailures()}. Rendered
	 *                 as a note where that group's section would otherwise sit,
	 *                 the same way {@link AccountExporter} places an
	 *                 organization's. Without it, a 403 on one group reads
	 *                 identically to that group being empty — the case
	 *                 {@code branchProtections}, {@code rulesets} and
	 *                 {@code webhooks} being empty already makes {@code --fix}
	 *                 delete on a later run.
	 */
	public static PklNode entry(
			RepositoryState state,
			List<FetchFailures.Failure> failures,
			SchemaDefaults defaults
	) {
		ActualRepository actual = state.repository();
		Drifty.Repository base = defaults.repository();

		var members = new ArrayList<PklNode.Member>();
		members.add(Fields.required("name", state.name()).orElseThrow());
		// Right after `name`, as the schema orders Repository's fields, and
		// before the archived branch below: RepositoryChecker.fetchState reads
		// a group for an archived repository too, so an unreadable one has to
		// be excluded there as well.
		AccountExporter.addUnmanagedGroups(members, failures);
		members.addAll(repositoryMembers(actual, base));
		if (actual.archived()) {
			members.add(Fields.note(ARCHIVED_NOTE));
			// securityMembers and collectionMembers are skipped, but
			// RepositoryChecker.fetchState still reads branchProtections,
			// actionSecrets, actionVariables, environments, webhooks,
			// customProperties, collaborators and workflowPermissions for an
			// archived repository — only rulesets, pages and the five
			// security-flag endpoints are skipped there. Any of those groups'
			// own 403s would otherwise vanish along with the members they
			// would have sat beside.
			for (FetchFailures.Failure failure : failures) {
				members.add(
						Fields.note(failure.group() + ": " + failure.reason())
				);
			}
		} else {
			members.addAll(securityMembers(state, base, failures));
			members.addAll(collectionMembers(state, defaults, failures));
		}
		return new PklNode.Obj(members);
	}

	private static List<PklNode.Member> repositoryMembers(
			ActualRepository actual,
			Drifty.Repository base
	) {
		String visibility = wire(actual.visibility());
		return Fields.members(
				Fields.field("archived", actual.archived(), base.archived),
				Fields.field(
						"description",
						actual.description(),
						base.description
				),
				Fields.field(
						"homepageUrl",
						actual.homepage(),
						base.homepageUrl
				),
				Fields.field(
						"visibility",
						visibility,
						base.visibility.toString()
				),
				Fields.note(
						"visibility is " + visibility + " on GitHub; "
								+ VISIBILITY_NOT_WRITABLE,
						visibility,
						base.visibility.toString()
				),
				Fields.field(
						"defaultBranch",
						actual.defaultBranch(),
						base.defaultBranch
				),
				Fields.strings("topics", actual.topics(), base.topics),
				Fields.field("hasIssues", actual.hasIssues(), base.hasIssues),
				Fields.field(
						"hasProjects",
						actual.hasProjects(),
						base.hasProjects
				),
				Fields.field("hasWiki", actual.hasWiki(), base.hasWiki),
				Fields.field(
						"hasDiscussions",
						actual.hasDiscussions(),
						base.hasDiscussions
				),
				Fields.field(
						"isTemplate",
						actual.isTemplate(),
						base.isTemplate
				),
				actual.organizationOwned() ? Fields.field(
						"allowForking",
						actual.allowForking(),
						base.allowForking
				) : Optional.empty(),
				Fields.field(
						"webCommitSignoffRequired",
						actual.webCommitSignoffRequired(),
						base.webCommitSignoffRequired
				),
				Fields.field(
						"allowMergeCommit",
						actual.allowMergeCommit(),
						base.allowMergeCommit
				),
				Fields.field(
						"allowSquashMerge",
						actual.allowSquashMerge(),
						base.allowSquashMerge
				),
				Fields.field(
						"allowRebaseMerge",
						actual.allowRebaseMerge(),
						base.allowRebaseMerge
				),
				Fields.field(
						"allowAutoMerge",
						actual.allowAutoMerge(),
						base.allowAutoMerge
				),
				Fields.field(
						"allowUpdateBranch",
						actual.allowUpdateBranch(),
						base.allowUpdateBranch
				),
				Fields.field(
						"deleteBranchOnMerge",
						actual.deleteBranchOnMerge(),
						base.deleteBranchOnMerge
				),
				Fields.field(
						"squashMergeCommitTitle",
						actual.squashMergeCommitTitle(),
						base.squashMergeCommitTitle.toString()
				),
				Fields.field(
						"squashMergeCommitMessage",
						actual.squashMergeCommitMessage(),
						base.squashMergeCommitMessage.toString()
				),
				Fields.field(
						"mergeCommitTitle",
						actual.mergeCommitTitle(),
						base.mergeCommitTitle.toString()
				),
				Fields.field(
						"mergeCommitMessage",
						actual.mergeCommitMessage(),
						base.mergeCommitMessage.toString()
				)
		);
	}

	/**
	 * Only called for a repository that is not archived; see the class comment
	 * for why an archived one skips this entirely.
	 * <p>
	 * The five booleans up front are each their own endpoint and their own
	 * {@code GroupName}, unlike the {@code secretScanning*}/{@code
	 * advancedSecurity} block below them, which rides along on the repository
	 * details response {@code RepositoryChecker.fetchState} always fetches — so
	 * only the five get a failure note of their own; the rest cannot fail
	 * independently of the repository details read that
	 * {@code RepositoryExporter} is never called without.
	 */
	private static List<PklNode.Member> securityMembers(
			RepositoryState state,
			Drifty.Repository base,
			List<FetchFailures.Failure> failures
	) {
		ActualSecurityAndAnalysis sa = state.securityAndAnalysis();
		var members = new ArrayList<PklNode.Member>();
		Fields.field(
				"vulnerabilityAlerts",
				state.vulnerabilityAlerts(),
				base.vulnerabilityAlerts
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.VULNERABILITY_ALERTS
		);
		Fields.field(
				"automatedSecurityFixes",
				state.automatedSecurityFixes(),
				base.automatedSecurityFixes
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.AUTOMATED_SECURITY_FIXES
		);
		Fields.field(
				"immutableReleases",
				state.immutableReleases(),
				base.immutableReleases
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.IMMUTABLE_RELEASES
		);
		Fields.field(
				"privateVulnerabilityReporting",
				state.privateVulnerabilityReporting(),
				base.privateVulnerabilityReporting
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.PRIVATE_VULNERABILITY_REPORTING
		);
		Fields.field(
				"codeScanningDefaultSetup",
				state.codeScanningDefaultSetup(),
				base.codeScanningDefaultSetup
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.CODE_SCANNING_DEFAULT_SETUP
		);
		members.addAll(
				Fields.members(
						Fields.field(
								"secretScanning",
								sa.secretScanning(),
								base.secretScanning
						),
						Fields.field(
								"secretScanningPushProtection",
								sa.secretScanningPushProtection(),
								base.secretScanningPushProtection
						),
						Fields.field(
								"secretScanningValidityChecks",
								sa.secretScanningValidityChecks(),
								base.secretScanningValidityChecks
						),
						Fields.field(
								"secretScanningNonProviderPatterns",
								sa.secretScanningNonProviderPatterns(),
								base.secretScanningNonProviderPatterns
						),
						Fields.field(
								"advancedSecurity",
								sa.advancedSecurity(),
								base.advancedSecurity
						),
						Fields.field(
								"secretScanningAiDetection",
								sa.secretScanningAiDetection(),
								base.secretScanningAiDetection
						),
						Fields.field(
								"secretScanningDelegatedAlertDismissal",
								sa.secretScanningDelegatedAlertDismissal(),
								base.secretScanningDelegatedAlertDismissal
						),
						Fields.field(
								"secretScanningDelegatedBypass",
								sa.secretScanningDelegatedBypass(),
								base.secretScanningDelegatedBypass
						)
				)
		);
		Fields.objects(
				"secretScanningDelegatedBypassReviewers",
				bypassReviewers(sa.bypassReviewers())
		).ifPresent(members::add);
		if (state.workflowPermissions() != null) {
			members.addAll(
					workflowPermissionsMembers(
							state.workflowPermissions(),
							base
					)
			);
		}
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.WORKFLOW_PERMISSIONS
		);
		return members;
	}

	/**
	 * Only called for a repository that is not archived, for the reason
	 * {@link #securityMembers} is: once {@code archived = true},
	 * {@code RepositoryChecker.createDriftGroups} runs only
	 * {@code ArchivedDriftGroup}, so none of these groups are compared against
	 * this file again either.
	 * <p>
	 * Order follows {@code config/drifty.pkl}'s {@code Repository} class:
	 * {@code pages}, {@code actionsSecrets}, {@code actionsVariables},
	 * {@code branchProtections}, {@code rulesets}, {@code environments},
	 * {@code webhooks}, {@code customProperties},
	 * {@code customMultiSelectProperties}, {@code collaborators},
	 * {@code teamPermissions}.
	 */
	private static List<PklNode.Member> collectionMembers(
			RepositoryState state,
			SchemaDefaults defaults,
			List<FetchFailures.Failure> failures
	) {
		var members = new ArrayList<PklNode.Member>();

		state.pages()
				.ifPresent(
						actualPages -> members.add(
								new PklNode.Field(
										"pages",
										PagesExporter.node(
												actualPages,
												defaults.pages()
										)
								)
						)
				);
		AccountExporter
				.addFailureNote(members, failures, Drifty.GroupName.PAGES);

		addActionsSecrets(members, state.actionSecrets());
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.ACTION_SECRETS
		);

		Fields.mapping(
				"actionsVariables",
				actionsVariableEntries(state.actionVariables())
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.ACTION_VARIABLES
		);

		Fields.mapping(
				"branchProtections",
				branchProtectionEntries(
						state.branchProtections(),
						defaults.branchProtection()
				)
		).ifPresent(members::add);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.BRANCH_PROTECTION
		);

		Fields.mapping(
				"rulesets",
				state.rulesets()
						.stream()
						.sorted(Comparator.comparing(ActualRuleset::name))
						.map(
								ruleset -> RulesetExporter
										.entry(ruleset, defaults, false)
						)
						.toList()
		).ifPresent(members::add);
		AccountExporter
				.addFailureNote(members, failures, Drifty.GroupName.RULESETS);

		Fields.mapping(
				"environments",
				environmentEntries(state, defaults.environment())
		).ifPresent(members::add);
		// One mapping serves three independently-guarded groups, the same way
		// RepositoryChecker.fetchState reads it: each can fail on its own, so
		// each gets its own note where the shared section sits.
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.ENVIRONMENT_CONFIG
		);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.ENVIRONMENT_SECRETS
		);
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.ENVIRONMENT_VARIABLES
		);

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
		AccountExporter
				.addFailureNote(members, failures, Drifty.GroupName.WEBHOOKS);

		addCustomProperties(members, state.customPropertyValues());
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.CUSTOM_PROPERTIES
		);

		if (state.collaborators() != null) {
			Fields.mapping(
					"collaborators",
					collaboratorEntries(state.collaborators().users())
			).ifPresent(members::add);
			Fields.mapping(
					"teamPermissions",
					collaboratorEntries(state.collaborators().teams())
			).ifPresent(members::add);
		}
		AccountExporter.addFailureNote(
				members,
				failures,
				Drifty.GroupName.COLLABORATORS
		);

		return members;
	}

	private static void addActionsSecrets(
			List<PklNode.Member> members,
			List<ActualSecret> secrets
	) {
		Fields.strings(
				"actionsSecrets",
				secrets.stream().map(ActualSecret::name).toList(),
				List.of()
		).ifPresent(members::add);
		if (!secrets.isEmpty()) {
			members.add(Fields.note(ACTIONS_SECRET_VALUES_NOTE));
		}
	}

	private static List<PklNode.Member> actionsVariableEntries(
			List<ActualVariable> variables
	) {
		return variables.stream()
				.sorted(Comparator.comparing(ActualVariable::name))
				.map(
						variable -> Fields
								.entry(variable.name(), variable.value())
				)
				.toList();
	}

	private static List<PklNode.Member> branchProtectionEntries(
			Map<String, ActualBranchProtection> branchProtections,
			Drifty.BranchProtection base
	) {
		return branchProtections.entrySet()
				.stream()
				.sorted(Map.Entry.comparingByKey())
				.map(
						entry -> BranchProtectionExporter
								.entry(entry.getKey(), entry.getValue(), base)
				)
				.toList();
	}

	private static List<PklNode.Member> environmentEntries(
			RepositoryState state,
			Drifty.Environment base
	) {
		return state.environments()
				.entrySet()
				.stream()
				.sorted(Map.Entry.comparingByKey())
				.map(
						entry -> EnvironmentExporter.entry(
								entry.getKey(),
								entry.getValue(),
								state.environmentSecrets()
										.getOrDefault(
												entry.getKey(),
												List.of()
										),
								state.environmentVariables()
										.getOrDefault(
												entry.getKey(),
												List.of()
										),
								base
						)
				)
				.toList();
	}

	/**
	 * {@code CustomPropertiesDriftGroup} keeps single-valued and multi-select
	 * property values in two separate maps because the schema does — a
	 * {@code multi_select} property's current value lives in
	 * {@link ActualCustomPropertyValue#values()}, every other type's in
	 * {@link ActualCustomPropertyValue#value()} — so a value is routed by which
	 * of the two GitHub actually populated, and a property GitHub lists with
	 * neither (never set on this repository) contributes nothing.
	 */
	private static void addCustomProperties(
			List<PklNode.Member> members,
			List<ActualCustomPropertyValue> values
	) {
		var single = new ArrayList<PklNode.Member>();
		var multi = new ArrayList<PklNode.Member>();
		values.stream()
				.sorted(Comparator.comparing(ActualCustomPropertyValue::name))
				.forEach(value -> {
					if (!value.values().isEmpty()) {
						multi.add(
								new PklNode.Field(
										value.name(),
										new PklNode.Listing(
												value.values()
														.stream()
														.sorted()
														.<PklNode>map(
																PklNode.Scalar::of
														)
														.toList(),
												false
										)
								)
						);
					} else if (value.value() != null) {
						single.add(
								new PklNode.Field(
										value.name(),
										PklNode.Scalar.of(value.value())
								)
						);
					}
				});
		Fields.mapping("customProperties", single).ifPresent(members::add);
		Fields.mapping("customMultiSelectProperties", multi)
				.ifPresent(members::add);
	}

	/**
	 * {@code ActualCollaborators} already carries each permission in the
	 * config's own vocabulary ({@code pull}, {@code triage}, {@code push},
	 * {@code maintain}, {@code admin}) — see its class comment — so this writes
	 * the string straight through rather than translating it, the way
	 * {@code AccountExporter.memberEntry} writes a member's role.
	 */
	private static List<PklNode.Member> collaboratorEntries(
			Map<String, String> permissions
	) {
		return permissions.entrySet()
				.stream()
				.sorted(Map.Entry.comparingByKey())
				.map(e -> Fields.entry(e.getKey(), e.getValue()))
				.toList();
	}

	/**
	 * No schema default exists for a single reviewer's fields, mirroring
	 * {@code CodeSecurityConfigurationExporter}'s reviewers: the drift group
	 * compares the whole reviewer as one string, not field by field, so there
	 * is nothing to diff against here either.
	 */
	private static List<PklNode> bypassReviewers(
			List<BypassReviewer> reviewers
	) {
		return reviewers.stream()
				.sorted(Comparator.comparing(BypassReviewer::toString))
				.<PklNode>map(
						reviewer -> new PklNode.Obj(
								List.of(
										required(
												"reviewerId",
												reviewer.reviewerId()
										),
										required(
												"reviewerType",
												reviewer.reviewerType()
										)
								)
						)
				)
				.toList();
	}

	private static List<PklNode.Member> workflowPermissionsMembers(
			ActualWorkflowPermissions actual,
			Drifty.Repository base
	) {
		return Fields.members(
				Fields.field(
						"defaultWorkflowPermissions",
						AccountExporter
								.wire(actual.defaultWorkflowPermissions()),
						base.defaultWorkflowPermissions.toString()
				),
				Fields.field(
						"canApprovePullRequestReviews",
						actual.canApprovePullRequestReviews(),
						base.canApprovePullRequestReviews
				)
		);
	}

	private static PklNode.Member required(String name, long value) {
		return Fields.required(name, value).orElseThrow();
	}

	private static PklNode.Member required(String name, String value) {
		return Fields.required(name, value).orElseThrow();
	}

	/**
	 * GitHub's visibility spells its constants upper-case
	 * ({@link RepositoryVisibility#PUBLIC}); the schema spells the same union
	 * lower-case ({@code Drifty.Visibility.PUBLIC.toString()} is
	 * {@code "public"}). Comparing the two as enums would compare like with
	 * unlike, so this maps to the schema's own spelling first.
	 */
	private static String wire(RepositoryVisibility value) {
		return switch (value) {
		case PUBLIC -> "public";
		case PRIVATE -> "private";
		case INTERNAL -> "internal";
		};
	}

}
