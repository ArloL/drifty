package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.StatusCheck;

class RulesetExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	/**
	 * Every field at its schema default except {@code name} and {@code target}
	 * — the two a case needs to name a ruleset and pick a shape. Individual
	 * tests vary one or two fields on top with the {@code with*} overloads
	 * below, rather than repeating all 35 positional arguments per case.
	 */
	private static ActualRuleset ruleset(String name, String target) {
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
				null,
				null,
				null,
				null,
				null,
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

	private static ActualRuleset withIncludePatterns(
			ActualRuleset base,
			Set<String> includePatterns
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				includePatterns,
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withExcludePatterns(
			ActualRuleset base,
			Set<String> excludePatterns
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				excludePatterns,
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withRequiredLinearHistory(
			ActualRuleset base,
			boolean requiredLinearHistory
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				requiredLinearHistory,
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withMaxFileSize(
			ActualRuleset base,
			Integer maxFileSize
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				maxFileSize,
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withPullRequest(
			ActualRuleset base,
			ActualRuleset.PullRequest pullRequest
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				pullRequest,
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withMergeQueue(
			ActualRuleset base,
			ActualRuleset.MergeQueue mergeQueue
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				mergeQueue,
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withRequiredStatusChecks(
			ActualRuleset base,
			Set<StatusCheck> requiredStatusChecks
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				requiredStatusChecks,
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withRequiredCodeScanningTools(
			ActualRuleset base,
			Set<String> tools
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				tools,
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withCommitMessagePattern(
			ActualRuleset base,
			ActualRuleset.RulePattern commitMessagePattern
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				commitMessagePattern,
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withWorkflows(
			ActualRuleset base,
			Set<ActualRuleset.Workflow> workflows
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				workflows,
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withBypassActors(
			ActualRuleset base,
			List<ActualRuleset.BypassActor> bypassActors
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				bypassActors,
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withRepositoryNameInclude(
			ActualRuleset base,
			Set<String> repositoryNameInclude
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				repositoryNameInclude,
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

	private static ActualRuleset withRepositoryPropertyInclude(
			ActualRuleset base,
			Set<ActualRuleset.PropertyCondition> repositoryPropertyInclude
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				base.pullRequest(),
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				repositoryPropertyInclude,
				base.repositoryPropertyExclude()
		);
	}

	@Test
	void keyedByNameWithOnlyTheDriftedRules() {
		var actual = withRequiredLinearHistory(
				withIncludePatterns(
						ruleset("main", "branch"),
						Set.of("refs/heads/main")
				),
				true
		);

		PklNode.Member member = RulesetExporter.entry(actual, DEFAULTS, false);

		var field = (PklNode.Field) member;
		assertThat(field.name()).isEqualTo("main");
		assertThat(PklWriter.write(field.value())).isEqualTo("""
				includePatterns = new Listing {
				  "refs/heads/main"
				}
				requiredLinearHistory = true
				""");
	}

	@Test
	void aPushRulesetHasNoRefConditions() {
		// Non-empty on purpose: Fields.strings would omit an empty collection
		// on its own, so an empty fixture here would pass whether or not the
		// target guard exists. Only a non-empty set proves the guard fires.
		var push = withExcludePatterns(
				withIncludePatterns(
						withMaxFileSize(ruleset("no-big-files", "push"), 100),
						Set.of("refs/heads/main")
				),
				Set.of("refs/heads/release-*")
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(push, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				target = "push"
				maxFileSize = 100
				""");
	}

	@Test
	void aPullRequestRuleIsEmittedEvenWhenAllItsFieldsAreDefault() {
		var pr = new ActualRuleset.PullRequest(
				0,
				false,
				false,
				false,
				false,
				Set.of()
		);
		var withPr = withPullRequest(
				withIncludePatterns(
						ruleset("main", "branch"),
						Set.of("refs/heads/main")
				),
				pr
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withPr, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).contains("pullRequest {");
	}

	@Test
	void aPullRequestSubFieldDifferingFromDefaultIsEmitted() {
		// Every other sub-field stays at PullRequestRule's default (0, false
		// x4, empty set), so a wrong field/default pairing among the six
		// would either wrongly omit this one or wrongly emit another.
		var pr = new ActualRuleset.PullRequest(
				2,
				false,
				false,
				false,
				false,
				Set.of()
		);
		var withPr = withPullRequest(
				withIncludePatterns(
						ruleset("main", "branch"),
						Set.of("refs/heads/main")
				),
				pr
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withPr, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				includePatterns = new Listing {
				  "refs/heads/main"
				}
				pullRequest {
				  requiredApprovingReviewCount = 2
				}
				""");
	}

	@Test
	void aMergeQueueRuleIsEmittedEvenWhenAllItsFieldsAreDefault() {
		var mq = new ActualRuleset.MergeQueue(
				60,
				"ALLGREEN",
				5,
				5,
				"MERGE",
				1,
				5
		);
		var withMq = withMergeQueue(
				withIncludePatterns(
						ruleset("main", "branch"),
						Set.of("refs/heads/main")
				),
				mq
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withMq, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).contains("mergeQueue {");
	}

	@Test
	void aMergeQueueSubFieldDifferingFromDefaultIsEmitted() {
		// Every other sub-field stays at MergeQueueRule's default (60, 5, 5,
		// "MERGE", 1, 5), so a wrong field/default pairing among the seven
		// would either wrongly omit this one or wrongly emit another.
		var mq = new ActualRuleset.MergeQueue(
				60,
				"HEADGREEN",
				5,
				5,
				"MERGE",
				1,
				5
		);
		var withMq = withMergeQueue(
				withIncludePatterns(
						ruleset("main", "branch"),
						Set.of("refs/heads/main")
				),
				mq
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withMq, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				includePatterns = new Listing {
				  "refs/heads/main"
				}
				mergeQueue {
				  groupingStrategy = "HEADGREEN"
				}
				""");
	}

	@Test
	void aRequiredStatusCheckOmitsANullAppId() {
		var withCheck = withRequiredStatusChecks(
				ruleset("main", "branch"),
				Set.of(new StatusCheck("build", null))
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withCheck, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				requiredStatusChecks {
				  new {
				    context = "build"
				  }
				}
				""");
	}

	@Test
	void codeScanningEmitsOnlyTheToolNameWithANote() {
		var withTools = withRequiredCodeScanningTools(
				ruleset("main", "branch"),
				Set.of("codeql")
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withTools, DEFAULTS, false);
		String written = PklWriter.write(field.value());

		// The note wraps at 80 columns, so it is checked in the two pieces
		// that land either side of the wrap rather than as one substring.
		assertThat(written).contains("requiredCodeScanning {")
				.contains("tool = \"codeql\"")
				.contains("code scanning alert thresholds are not read back")
				.contains("names only");
	}

	/**
	 * The whole rule, not just its text: a fix built from a config missing the
	 * operator and negate flag would PUT a rule GitHub does not have, which is
	 * what a note in their place used to risk.
	 */
	@Test
	void aRulePatternIsExportedAsARealObjectRatherThanANote() {
		var withPattern = withCommitMessagePattern(
				ruleset("main", "branch"),
				new ActualRuleset.RulePattern(
						"no-jira",
						true,
						"regex",
						"^JIRA-"
				)
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withPattern, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				commitMessagePattern {
				  name = "no-jira"
				  negate = true
				  operator = "regex"
				  pattern = "^JIRA-"
				}
				""");
	}

	/**
	 * {@code operator} and {@code pattern} are always written — GitHub never
	 * reports a pattern rule without them — but {@code name} and {@code negate}
	 * have schema defaults and are omitted when GitHub's values match them, the
	 * same as any other defaulted field.
	 */
	@Test
	void aRulePatternAtItsOtherFieldsDefaultsWritesOnlyOperatorAndPattern() {
		var withPattern = withCommitMessagePattern(
				ruleset("main", "branch"),
				new ActualRuleset.RulePattern(
						null,
						false,
						"starts_with",
						"feat:"
				)
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(withPattern, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				commitMessagePattern {
				  operator = "starts_with"
				  pattern = "feat:"
				}
				""");
	}

	@Test
	void workflowsAndBypassActorsAreFullyRequiredFields() {
		var actual = withBypassActors(
				withWorkflows(
						ruleset("main", "branch"),
						Set.of(new ActualRuleset.Workflow("a.yml", 42L, null))
				),
				List.of(new ActualRuleset.BypassActor("Team", 7L, "always"))
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(actual, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				workflows {
				  new {
				    path = "a.yml"
				    repositoryId = 42
				    ref = null
				  }
				}
				bypassActors {
				  new {
				    actorId = 7
				    actorType = "Team"
				    bypassMode = "always"
				  }
				}
				""");
	}

	@Test
	void aBypassActorWithNoIdWritesAnExplicitNullRatherThanCrashing() {
		// GitHub identifies an OrganizationAdmin bypass actor by role alone,
		// so actor_id comes back null on the wire — the case that used to
		// NPE unboxing a null Long into the primitive required(String,long).
		var actual = withBypassActors(
				ruleset("main", "branch"),
				List.of(
						new ActualRuleset.BypassActor(
								"OrganizationAdmin",
								null,
								"always"
						)
				)
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(actual, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				bypassActors {
				  new {
				    actorId = null
				    actorType = "OrganizationAdmin"
				    bypassMode = "always"
				  }
				}
				""");
	}

	@Test
	void anOrganizationRulesetWritesTheRepositorySelectionFields() {
		// source: "custom" matches PropertyCondition's schema default, so it
		// is correctly absent here — only name and propertyValues have no
		// default and are always written.
		var actual = withRepositoryPropertyInclude(
				withRepositoryNameInclude(
						ruleset("protect-main", "branch"),
						Set.of("service-*")
				),
				Set.of(
						new ActualRuleset.PropertyCondition(
								"team",
								Set.of("platform"),
								"custom"
						)
				)
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(actual, DEFAULTS, true);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				repositoryNameInclude = new Listing {
				  "service-*"
				}
				repositoryPropertyInclude {
				  new {
				    name = "team"
				    propertyValues {
				      "platform"
				    }
				  }
				}
				""");
	}

	@Test
	void aPropertyConditionSourceDifferingFromDefaultIsEmitted() {
		var actual = withRepositoryPropertyInclude(
				ruleset("protect-main", "branch"),
				Set.of(
						new ActualRuleset.PropertyCondition(
								"team",
								Set.of("platform"),
								"system"
						)
				)
		);

		var field = (PklNode.Field) RulesetExporter
				.entry(actual, DEFAULTS, true);

		assertThat(PklWriter.write(field.value()))
				.contains("source = \"system\"");
	}

}
