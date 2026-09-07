package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.OrganizationState;
import io.github.arlol.githubcheck.RepositoryState;
import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.actual.ActualSelectedActions;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Every schema field has an exporter line.
 * <p>
 * A field added to {@code config/drifty.pkl} that no exporter emits is absent
 * from every exported file, and nothing else notices: the field simply keeps
 * the schema's own default, {@code ExportRoundTripTest} loads that default
 * straight back, and the comparison agrees with itself. This is the export's
 * counterpart to {@code DriftPathNamespacingTest} — the one place that fails
 * instead of staying quiet.
 * <p>
 * This proves an exporter line exists for the field, nothing more: a fixture
 * whose value renders under the wrong name, or a line that writes the wrong
 * field's value, still makes the field name it copied from show up in the text
 * and would pass here. Catching a wrong value is each exporter's own unit
 * tests' job, not this one's.
 */
class SchemaCoverageTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static final Set<String> NOT_EXPORTED = Set.of(
			// An export manages every group, so `managed` never has a value to
			// disagree with GitHub about. Shared by Organization and
			// Repository.
			"managed",
			// ActualTypes flattens a rule pattern to its text before it reaches
			// the comparison, so the operator and negate flag GitHub reports
			// are
			// already gone by export time; RulesetExporter emits a note in the
			// field's place instead of inventing them. See RulesetExporterTest.
			"commitMessagePattern",
			"commitAuthorEmailPattern",
			"committerEmailPattern",
			"branchNamePattern",
			"tagNamePattern"
	);

	@Test
	void everyOrganizationFieldIsEmittedForAnAllDriftedOrganization() {
		String exported = PklWriter.write(
				new PklNode.Obj(
						AccountExporter.organizationMembers(
								allDriftedOrganizationState(),
								List.of(),
								List.of(dummyRepositoryEntry()),
								DEFAULTS
						)
				)
		);

		assertEveryFieldAppears(Drifty.Organization.class, exported);
	}

	/**
	 * {@code archived} is exported as a field only when it differs from its
	 * schema default of {@code false} — and once it does, every other field
	 * this exporter covers is skipped in favour of a single note (see
	 * {@code RepositoryExporter}'s class comment). The two states below are
	 * mutually exclusive shapes of the same repository, so their outputs are
	 * concatenated before checking field names rather than exported from one
	 * fixture that cannot exist.
	 */
	@Test
	void everyRepositoryFieldIsEmittedForAnAllDriftedRepository() {
		String notArchived = PklWriter.write(
				RepositoryExporter
						.entry(allDriftedRepositoryState(), List.of(), DEFAULTS)
		);
		String archived = PklWriter.write(
				RepositoryExporter
						.entry(archivedRepositoryState(), List.of(), DEFAULTS)
		);

		assertEveryFieldAppears(
				Drifty.Repository.class,
				notArchived + archived
		);
	}

	@Test
	void everyRulesetFieldIsEmittedForAnAllDriftedRuleset() {
		PklNode.Field field = (PklNode.Field) RulesetExporter
				.entry(allDriftedRuleset(), DEFAULTS, false);
		String exported = PklWriter.write(field.value());

		assertEveryFieldAppears(Drifty.Ruleset.class, exported);
	}

	private static void assertEveryFieldAppears(
			Class<?> type,
			String exported
	) {
		assertThat(fieldNames(type))
				.filteredOn(name -> !NOT_EXPORTED.contains(name))
				.allSatisfy(name -> assertThat(exported).contains(name));
	}

	private static List<String> fieldNames(Class<?> type) {
		return Arrays.stream(type.getFields()).map(Field::getName).toList();
	}

	// ─── Organization fixture ────────────────────────────────────────────

	private static OrganizationState allDriftedOrganizationState() {
		return new OrganizationState(
				"acme",
				allDriftedSettings(),
				allDriftedActionsPermissions(),
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.READ,
						false
				),
				List.of(
						new ActualOrgSecret(
								"ci-token",
								"2024-01-01T00:00:00Z",
								SecretVisibility.SELECTED,
								List.of("widget")
						)
				),
				List.of(
						new ActualOrgVariable(
								"region",
								"us-east-1",
								SecretVisibility.ALL,
								List.of()
						)
				),
				List.of(
						new ActualWebhook(
								1L,
								"https://example.com/hook",
								"json",
								false,
								true,
								Set.of("push"),
								false,
								"2024-01-01T00:00:00Z"
						)
				),
				List.of(
						new ActualCustomProperty(
								"priority",
								"string",
								true,
								null,
								List.of(),
								"",
								List.of(),
								"org_actors"
						)
				),
				List.of(minimalRuleset("main", "branch")),
				List.of(
						new ActualCodeSecurityConfiguration(
								1L,
								"baseline",
								"",
								Map.of(),
								"enforced",
								null,
								null,
								Set.of(),
								"disabled",
								Set.of()
						)
				),
				List.of(
						new ActualTeam(
								1L,
								"platform",
								"platform",
								"",
								"closed",
								"notifications_enabled",
								null,
								Set.of(),
								Set.of()
						)
				),
				List.of(new ActualOrgMember("alice", "admin")),
				List.of(
						new ActualRunnerGroup(
								1L,
								"custom",
								"all",
								false,
								false,
								false,
								Set.of(),
								List.of()
						)
				)
		);
	}

	/** Every field {@code OrganizationExporter} covers, off its default. */
	private static ActualOrganization allDriftedSettings() {
		return new ActualOrganization(
				"Acme Corp",
				"Widgets, Inc.",
				"https://acme.example",
				"Acme LLC",
				"info@acme.example",
				"Springfield",
				"acmecorp",
				false,
				false,
				"admin",
				false,
				false,
				false,
				true,
				false,
				false,
				false,
				true,
				true,
				true,
				"develop",
				true,
				false,
				false,
				false,
				true,
				false,
				false,
				true,
				true
		);
	}

	private static ActualOrgActionsPermissions allDriftedActionsPermissions() {
		return new ActualOrgActionsPermissions(
				ActionsEnabledRepositories.SELECTED,
				AllowedActions.SELECTED,
				true,
				new ActualSelectedActions(false, true, List.of("api-*")),
				List.of("widget")
		);
	}

	private static PklNode dummyRepositoryEntry() {
		return new PklNode.Obj(
				List.of(Fields.required("name", "widget").orElseThrow())
		);
	}

	// ─── Repository fixture ──────────────────────────────────────────────

	private static RepositoryState allDriftedRepositoryState() {
		return new RepositoryState(
				new RepoRef("acme", "widget"),
				allDriftedRepository(),
				allDriftedSecurityAndAnalysis(),
				false,
				true,
				true,
				true,
				true,
				Map.of(
						"main",
						new ActualBranchProtection(
								true,
								false,
								false,
								false,
								false,
								false,
								false,
								false,
								false,
								Set.of(),
								Optional.empty(),
								Optional.empty()
						)
				),
				List.of(minimalRuleset("protect-main", "branch")),
				List.of(new ActualSecret("token", "2024-01-01T00:00:00Z")),
				Map.of("prod", new ActualEnvironment(10, false, true)),
				Map.of(),
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.READ,
						false
				),
				Optional.of(
						new ActualPages(
								"legacy",
								Optional.of(
										new ActualPages.Source("main", "/docs")
								),
								true
						)
				),
				List.of(new ActualVariable("region", "eu")),
				Map.of(),
				List.of(
						new ActualWebhook(
								1L,
								"https://a.example.com/hook",
								"json",
								false,
								true,
								Set.of("push"),
								false,
								null
						)
				),
				List.of(
						new ActualCustomPropertyValue(
								"team",
								"platform",
								List.of()
						),
						new ActualCustomPropertyValue(
								"environments",
								null,
								List.of("staging", "production")
						)
				),
				new ActualCollaborators(
						Map.of("alice", "admin"),
						Map.of("platform", "push")
				)
		);
	}

	/** Every {@code repositoryMembers} field, off its default. */
	private static ActualRepository allDriftedRepository() {
		return new ActualRepository(
				false,
				true,
				"Widgets, Inc.",
				"https://example.com",
				RepositoryVisibility.PRIVATE,
				"develop",
				List.of("alpha", "zebra"),
				false,
				false,
				false,
				true,
				true,
				false,
				true,
				false,
				false,
				false,
				true,
				true,
				true,
				SquashMergeCommitTitle.PR_TITLE,
				SquashMergeCommitMessage.PR_BODY,
				MergeCommitTitle.PR_TITLE,
				MergeCommitMessage.PR_BODY
		);
	}

	private static ActualSecurityAndAnalysis allDriftedSecurityAndAnalysis() {
		return new ActualSecurityAndAnalysis(
				false,
				false,
				true,
				true,
				true,
				true,
				true,
				true,
				List.of(new BypassReviewer("TEAM", 2L))
		);
	}

	/** A repository whose only drifted field is {@code archived} itself. */
	private static RepositoryState archivedRepositoryState() {
		ActualRepository base = allDriftedRepository();
		ActualRepository archived = new ActualRepository(
				true,
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		return new RepositoryState(
				new RepoRef("acme", "widget"),
				archived,
				allDriftedSecurityAndAnalysis(),
				false,
				true,
				true,
				true,
				true,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.READ,
						false
				),
				Optional.empty()
		);
	}

	// ─── Ruleset fixture ─────────────────────────────────────────────────

	/** Every field {@code Drifty.Ruleset} declares, off its schema default. */
	private static ActualRuleset allDriftedRuleset() {
		return new ActualRuleset(
				1L,
				"protect-main",
				"tag",
				"evaluate",
				Set.of("refs/heads/main"),
				Set.of("refs/heads/release-*"),
				true,
				true,
				true,
				true,
				true,
				true,
				true,
				true,
				Set.of(new StatusCheck("build", null)),
				new ActualRuleset.PullRequest(
						1,
						false,
						false,
						false,
						false,
						Set.of()
				),
				Set.of("codeql"),
				Set.of("production"),
				"",
				"",
				"",
				"",
				"",
				new ActualRuleset.MergeQueue(
						60,
						"ALLGREEN",
						5,
						5,
						"MERGE",
						1,
						5
				),
				Set.of(new ActualRuleset.Workflow("a.yml", 42L, null)),
				Set.of("secrets.yml"),
				255,
				Set.of(".exe"),
				100,
				List.of(new ActualRuleset.BypassActor("Team", 7L, "always")),
				Set.of(),
				Set.of(),
				false,
				Set.of(),
				Set.of()
		);
	}

	/**
	 * A ruleset with every field at its schema default except {@code target}.
	 * Used only to populate a collection whose own field coverage is not what
	 * the test is checking — {@code allDriftedRuleset} is.
	 */
	private static ActualRuleset minimalRuleset(String name, String target) {
		return new ActualRuleset(
				1L,
				name,
				target,
				"active",
				Set.of(),
				Set.of(),
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				null,
				Set.of(),
				Set.of(),
				"",
				"",
				"",
				"",
				"",
				null,
				Set.of(),
				Set.of(),
				null,
				Set.of(),
				null,
				List.of(),
				Set.of(),
				Set.of(),
				false,
				Set.of(),
				Set.of()
		);
	}

}
