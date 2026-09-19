package io.github.arlol.githubcheck.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * What GraphQL answers, rewritten as the REST shape the response records
 * already parse.
 * <p>
 * The account this was developed against exercises four of the twenty rule
 * types, one target and no bypass actor at all, so a live run agreeing with the
 * REST route is necessary and nowhere near sufficient. These are the rest of
 * them: each case is a GraphQL rule and the field it has to land in, and a
 * spelling guessed wrong here is a wrong answer rather than an error.
 */
class GraphQlShapeTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					true
			);

	// ─── Rulesets
	// ──────────────────────────────────────────────────────────

	@Test
	void aRulesetKeepsItsIdentityAndItsRefConditions() {
		RulesetDetailsResponse ruleset = ruleset("""
				"databaseId": 42, "name": "main-rules",
				"target": "BRANCH", "enforcement": "EVALUATE",
				"source": {"__typename": "Repository"},
				"conditions": {"refName": {
				  "include": ["refs/heads/main"], "exclude": ["refs/heads/wip"]
				}},
				"bypassActors": {"nodes": []},
				"rules": {"nodes": []}
				""");

		assertThat(ruleset.id()).isEqualTo(42);
		assertThat(ruleset.name()).isEqualTo("main-rules");
		assertThat(ruleset.target()).isEqualTo(RulesetTarget.BRANCH);
		assertThat(ruleset.enforcement())
				.isEqualTo(RulesetEnforcement.EVALUATE);
		assertThat(ruleset.sourceType())
				.isEqualTo(RulesetSourceType.REPOSITORY);
		assertThat(ruleset.conditions().refName().include())
				.containsExactly("refs/heads/main");
		assertThat(ruleset.conditions().refName().exclude())
				.containsExactly("refs/heads/wip");
	}

	/**
	 * An organization's ruleset reaches a repository's listing and is not the
	 * repository's to reconcile — {@code RepositoryChecker} drops it by this
	 * field, so assuming {@code Repository} would produce a fix that always
	 * fails.
	 */
	@ParameterizedTest
	@CsvSource({ "Repository,REPOSITORY", "Organization,ORGANIZATION" })
	void aRulesetSaysWhereItCameFrom(String typename, String expected) {
		RulesetDetailsResponse ruleset = ruleset("""
				"databaseId": 1, "name": "r", "target": "BRANCH",
				"enforcement": "ACTIVE",
				"source": {"__typename": "%s"},
				"bypassActors": {"nodes": []}, "rules": {"nodes": []}
				""".formatted(typename));

		assertThat(ruleset.sourceType())
				.isEqualTo(RulesetSourceType.valueOf(expected));
	}

	@ParameterizedTest
	@CsvSource(
		{ "CREATION,CREATION", "DELETION,DELETION",
				"REQUIRED_SIGNATURES,REQUIRED_SIGNATURES",
				"REQUIRED_LINEAR_HISTORY,REQUIRED_LINEAR_HISTORY",
				"NON_FAST_FORWARD,NON_FAST_FORWARD" }
	)
	void aRuleWithNoParametersIsItsTypeAlone(String type, String expected) {
		assertThat(rules("""
				{"type": "%s", "parameters": null}
				""".formatted(type))).extracting(Rule::type)
				.containsExactly(RulesetRuleType.valueOf(expected));
	}

	@Test
	void aPullRequestRuleKeepsEveryReviewSetting() {
		Rule.PullRequest.Parameters p = (Rule.PullRequest.Parameters) ((Rule.PullRequest) rules(
				"""
						{"type": "PULL_REQUEST", "parameters": {
						  "__typename": "PullRequestParameters",
						  "requiredApprovingReviewCount": 2,
						  "dismissStaleReviewsOnPush": true,
						  "requireCodeOwnerReview": true,
						  "requireLastPushApproval": true,
						  "requiredReviewThreadResolution": true,
						  "allowedMergeMethods": ["SQUASH", "REBASE"]
						}}
						"""
		).getFirst()).parameters();

		assertThat(p.requiredApprovingReviewCount()).isEqualTo(2);
		assertThat(p.dismissStaleReviewsOnPush()).isTrue();
		assertThat(p.requireCodeOwnerReview()).isTrue();
		assertThat(p.requireLastPushApproval()).isTrue();
		assertThat(p.requiredReviewThreadResolution()).isTrue();
		assertThat(p.allowedMergeMethods()).containsExactly("squash", "rebase");
	}

	/**
	 * A ruleset's status checks name their app as {@code integrationId} where a
	 * branch protection rule's use {@code app { databaseId }}.
	 */
	@Test
	void aStatusCheckRuleNamesItsAppByIntegrationId() {
		Rule.RequiredStatusChecks.Parameters p = ((Rule.RequiredStatusChecks) rules(
				"""
						{"type": "REQUIRED_STATUS_CHECKS", "parameters": {
						  "__typename": "RequiredStatusChecksParameters",
						  "strictRequiredStatusChecksPolicy": true,
						  "requiredStatusChecks": [
						    {"context": "build", "integrationId": 15368}
						  ]
						}}
						"""
		).getFirst()).parameters();

		assertThat(p.strictRequiredStatusChecksPolicy()).isTrue();
		assertThat(p.requiredStatusChecks()).singleElement()
				.satisfies(check -> {
					assertThat(check.context()).isEqualTo("build");
					assertThat(check.integrationId()).isEqualTo(15368);
				});
	}

	@Test
	void anUpdateRuleKeepsWhetherItAllowsFetchAndMerge() {
		assertThat(((Rule.Update) rules("""
				{"type": "UPDATE", "parameters": {
				  "__typename": "UpdateParameters",
				  "updateAllowsFetchAndMerge": true
				}}
				""").getFirst()).parameters().updateAllowsFetchAndMerge())
				.isTrue();
	}

	@ParameterizedTest
	@CsvSource(
		{ "COMMIT_MESSAGE_PATTERN,CommitMessagePatternParameters",
				"COMMIT_AUTHOR_EMAIL_PATTERN,CommitAuthorEmailPatternParameters",
				"COMMITTER_EMAIL_PATTERN,CommitterEmailPatternParameters",
				"BRANCH_NAME_PATTERN,BranchNamePatternParameters",
				"TAG_NAME_PATTERN,TagNamePatternParameters" }
	)
	void everyPatternRuleKeepsItsOperatorInTheWireSpelling(
			String type,
			String typename
	) {
		Rule rule = rules("""
				{"type": "%s", "parameters": {
				  "__typename": "%s",
				  "name": "no fixups", "negate": true,
				  "operator": "STARTS_WITH", "pattern": "fixup!"
				}}
				""".formatted(type, typename)).getFirst();

		Rule.PatternParameters p = switch (rule) {
		case Rule.CommitMessagePattern r -> r.parameters();
		case Rule.CommitAuthorEmailPattern r -> r.parameters();
		case Rule.CommitterEmailPattern r -> r.parameters();
		case Rule.BranchNamePattern r -> r.parameters();
		case Rule.TagNamePattern r -> r.parameters();
		default -> throw new AssertionError("not a pattern rule: " + rule);
		};
		assertThat(p.name()).isEqualTo("no fixups");
		assertThat(p.negate()).isTrue();
		assertThat(p.operator()).isEqualTo(RulePatternOperator.STARTS_WITH);
		assertThat(p.pattern()).isEqualTo("fixup!");
	}

	/** GraphQL spells two of these {@code ...Minutes} and REST does not. */
	@Test
	void aMergeQueueRuleKeepsBothOfItsMinuteFields() {
		Rule.MergeQueue.Parameters p = ((Rule.MergeQueue) rules("""
				{"type": "MERGE_QUEUE", "parameters": {
				  "__typename": "MergeQueueParameters",
				  "checkResponseTimeoutMinutes": 60,
				  "groupingStrategy": "ALLGREEN",
				  "maxEntriesToBuild": 5, "maxEntriesToMerge": 5,
				  "mergeMethod": "SQUASH",
				  "minEntriesToMerge": 1,
				  "minEntriesToMergeWaitMinutes": 5
				}}
				""").getFirst()).parameters();

		assertThat(p.checkResponseTimeoutMinutes()).isEqualTo(60);
		assertThat(p.minEntriesToMergeWaitMinutes()).isEqualTo(5);
		assertThat(p.groupingStrategy()).isEqualTo("allgreen");
		assertThat(p.mergeMethod()).isEqualTo("squash");
		assertThat(p.maxEntriesToBuild()).isEqualTo(5);
		assertThat(p.maxEntriesToMerge()).isEqualTo(5);
		assertThat(p.minEntriesToMerge()).isEqualTo(1);
	}

	@Test
	void theRemainingParameterisedRulesKeepTheirValues() {
		assertThat(((Rule.RequiredDeployments) rules("""
				{"type": "REQUIRED_DEPLOYMENTS", "parameters": {
				  "__typename": "RequiredDeploymentsParameters",
				  "requiredDeploymentEnvironments": ["prod"]
				}}
				""").getFirst()).parameters().requiredDeploymentEnvironments())
				.containsExactly("prod");

		assertThat(((Rule.CodeScanning) rules("""
				{"type": "CODE_SCANNING", "parameters": {
				  "__typename": "CodeScanningParameters",
				  "codeScanningTools": [{"tool": "CodeQL",
				    "alertsThreshold": "ERRORS",
				    "securityAlertsThreshold": "HIGH_OR_HIGHER"}]
				}}
				""").getFirst()).parameters().codeScanningTools())
				.singleElement()
				.satisfies(t -> assertThat(t.tool()).isEqualTo("CodeQL"));

		assertThat(((Rule.Workflows) rules("""
				{"type": "WORKFLOWS", "parameters": {
				  "__typename": "WorkflowsParameters",
				  "doNotEnforceOnCreate": false,
				  "workflows": [{"path": ".github/workflows/ci.yaml",
				    "ref": "refs/heads/main", "repositoryId": 7,
				    "sha": null}]
				}}
				""").getFirst()).parameters().workflows()).singleElement()
				.satisfies(w -> {
					assertThat(w.path()).isEqualTo(".github/workflows/ci.yaml");
					assertThat(w.repositoryId()).isEqualTo(7);
					assertThat(w.ref()).isEqualTo("refs/heads/main");
				});

		assertThat(((Rule.FilePathRestriction) rules("""
				{"type": "FILE_PATH_RESTRICTION", "parameters": {
				  "__typename": "FilePathRestrictionParameters",
				  "restrictedFilePaths": ["secrets/**"]
				}}
				""").getFirst()).parameters().restrictedFilePaths())
				.containsExactly("secrets/**");

		assertThat(((Rule.MaxFilePathLength) rules("""
				{"type": "MAX_FILE_PATH_LENGTH", "parameters": {
				  "__typename": "MaxFilePathLengthParameters",
				  "maxFilePathLength": 255
				}}
				""").getFirst()).parameters().maxFilePathLength())
				.isEqualTo(255);

		assertThat(((Rule.FileExtensionRestriction) rules("""
				{"type": "FILE_EXTENSION_RESTRICTION", "parameters": {
				  "__typename": "FileExtensionRestrictionParameters",
				  "restrictedFileExtensions": [".exe"]
				}}
				""").getFirst()).parameters().restrictedFileExtensions())
				.containsExactly(".exe");

		assertThat(((Rule.MaxFileSize) rules("""
				{"type": "MAX_FILE_SIZE", "parameters": {
				  "__typename": "MaxFileSizeParameters",
				  "maxFileSize": 100
				}}
				""").getFirst()).parameters().maxFileSize()).isEqualTo(100);
	}

	/**
	 * REST names a bypass actor with an {@code actor_type} string and an id;
	 * GraphQL uses a union for two of the four and a boolean for the others.
	 */
	@Test
	void everyShapeOfBypassActorKeepsItsTypeAndId() {
		List<RulesetDetailsResponse.BypassActor> actors = ruleset("""
				"databaseId": 1, "name": "r", "target": "BRANCH",
				"enforcement": "ACTIVE",
				"source": {"__typename": "Repository"},
				"rules": {"nodes": []},
				"bypassActors": {"nodes": [
				  {"bypassMode": "ALWAYS", "organizationAdmin": true,
				   "deployKey": false, "repositoryRoleDatabaseId": null,
				   "actor": null},
				  {"bypassMode": "PULL_REQUEST", "organizationAdmin": false,
				   "deployKey": false, "repositoryRoleDatabaseId": 5,
				   "actor": null},
				  {"bypassMode": "ALWAYS", "organizationAdmin": false,
				   "deployKey": false, "repositoryRoleDatabaseId": null,
				   "actor": {"__typename": "Team", "databaseId": 11}},
				  {"bypassMode": "ALWAYS", "organizationAdmin": false,
				   "deployKey": false, "repositoryRoleDatabaseId": null,
				   "actor": {"__typename": "App", "databaseId": 22}}
				]}
				""").bypassActors();

		assertThat(actors)
				.extracting(
						RulesetDetailsResponse.BypassActor::actorType,
						RulesetDetailsResponse.BypassActor::actorId,
						RulesetDetailsResponse.BypassActor::bypassMode
				)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(
								RulesetDetailsResponse.BypassActor.ActorType.ORGANIZATION_ADMIN,
								1L,
								RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
						),
						org.assertj.core.groups.Tuple.tuple(
								RulesetDetailsResponse.BypassActor.ActorType.REPOSITORY_ROLE,
								5L,
								RulesetDetailsResponse.BypassActor.BypassMode.PULL_REQUEST
						),
						org.assertj.core.groups.Tuple.tuple(
								RulesetDetailsResponse.BypassActor.ActorType.TEAM,
								11L,
								RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
						),
						org.assertj.core.groups.Tuple.tuple(
								RulesetDetailsResponse.BypassActor.ActorType.INTEGRATION,
								22L,
								RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
						)
				);
	}

	// ─── Branch protection
	// ─────────────────────────────────────────────────

	/**
	 * GitHub omits a section rather than returning it disabled, and
	 * {@code ActualTypes} reads the omission as false — so a setting that is
	 * off has to produce no section at all.
	 */
	@Test
	void everyToggleBecomesItsOwnEnabledSection() {
		BranchProtectionResponse on = protection(
				"""
						"isAdminEnforced": true, "requiresLinearHistory": true,
						"allowsForcePushes": true, "allowsDeletions": true,
						"blocksCreations": true, "requiresConversationResolution": true,
						"requiresCommitSignatures": true, "lockBranch": true,
						"lockAllowsFetchAndMerge": true,
						"requiresStatusChecks": false, "requiresApprovingReviews": false,
						"restrictsPushes": false
						"""
		);

		assertThat(on.enforceAdmins().enabled()).isTrue();
		assertThat(on.requiredLinearHistory().enabled()).isTrue();
		assertThat(on.allowForcePushes().enabled()).isTrue();
		assertThat(on.allowDeletions().enabled()).isTrue();
		assertThat(on.blockCreations().enabled()).isTrue();
		assertThat(on.requiredConversationResolution().enabled()).isTrue();
		assertThat(on.requiredSignatures().enabled()).isTrue();
		assertThat(on.lockBranch().enabled()).isTrue();
		// lockAllowsFetchAndMerge is REST's allow_fork_syncing.
		assertThat(on.allowForkSyncing().enabled()).isTrue();

		BranchProtectionResponse off = protection(
				"""
						"isAdminEnforced": false, "requiresLinearHistory": false,
						"allowsForcePushes": false, "allowsDeletions": false,
						"blocksCreations": false, "requiresConversationResolution": false,
						"requiresCommitSignatures": false, "lockBranch": false,
						"lockAllowsFetchAndMerge": false,
						"requiresStatusChecks": false, "requiresApprovingReviews": false,
						"restrictsPushes": false
						"""
		);

		assertThat(off.enforceAdmins()).isNull();
		assertThat(off.lockBranch()).isNull();
		assertThat(off.allowForkSyncing()).isNull();
		assertThat(off.requiredStatusChecks()).isNull();
		assertThat(off.requiredPullRequestReviews()).isNull();
		assertThat(off.restrictions()).isNull();
	}

	/**
	 * {@code lockAllowsFetchAndMerge} is REST's {@code allow_fork_syncing} and
	 * sits next to {@code lockBranch}, which is the one it would be confused
	 * with. They are set apart here so that confusing them fails.
	 */
	@Test
	void forkSyncingComesFromLockAllowsFetchAndMergeNotLockBranch() {
		BranchProtectionResponse syncing = protection(
				"""
						"lockBranch": false, "lockAllowsFetchAndMerge": true,
						"requiresStatusChecks": false, "requiresApprovingReviews": false,
						"restrictsPushes": false
						"""
		);

		assertThat(syncing.allowForkSyncing().enabled()).isTrue();
		assertThat(syncing.lockBranch()).isNull();

		BranchProtectionResponse locked = protection(
				"""
						"lockBranch": true, "lockAllowsFetchAndMerge": false,
						"requiresStatusChecks": false, "requiresApprovingReviews": false,
						"restrictsPushes": false
						"""
		);

		assertThat(locked.lockBranch().enabled()).isTrue();
		assertThat(locked.allowForkSyncing()).isNull();
	}

	/**
	 * A branch protection rule names its app as {@code app { databaseId }},
	 * where the ruleset rule of the same name uses {@code integrationId}.
	 */
	@Test
	void statusChecksNameTheirAppByDatabaseId() {
		BranchProtectionResponse.RequiredStatusChecks checks = protection(
				"""
						"requiresStatusChecks": true, "requiresStrictStatusChecks": true,
						"requiredStatusChecks": [
						  {"context": "build", "app": {"databaseId": 15368}},
						  {"context": "manual", "app": null}
						],
						"requiresApprovingReviews": false, "restrictsPushes": false
						"""
		).requiredStatusChecks();

		assertThat(checks.strict()).isTrue();
		assertThat(checks.checks()).extracting(
				BranchProtectionResponse.RequiredStatusChecks.StatusCheck::context,
				BranchProtectionResponse.RequiredStatusChecks.StatusCheck::appId
		)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("build", 15368),
						org.assertj.core.groups.Tuple.tuple("manual", null)
				);
	}

	@Test
	void reviewSettingsAndTheirActorListsSurvive() {
		BranchProtectionResponse.RequiredPullRequestReviews reviews = protection(
				"""
						"requiresStatusChecks": false, "restrictsPushes": false,
						"requiresApprovingReviews": true,
						"requiredApprovingReviewCount": 2,
						"dismissesStaleReviews": true,
						"requiresCodeOwnerReviews": true,
						"requireLastPushApproval": true,
						"restrictsReviewDismissals": true,
						"reviewDismissalAllowances": {"nodes": [
						  {"actor": {"__typename": "User", "login": "alice"}}
						]},
						"bypassPullRequestAllowances": {"nodes": [
						  {"actor": {"__typename": "Team", "slug": "platform"}},
						  {"actor": {"__typename": "App", "slug": "dependabot"}}
						]}
						"""
		).requiredPullRequestReviews();

		assertThat(reviews.requiredApprovingReviewCount()).isEqualTo(2);
		assertThat(reviews.dismissStaleReviews()).isTrue();
		assertThat(reviews.requireCodeOwnerReviews()).isTrue();
		assertThat(reviews.requireLastPushApproval()).isTrue();
		assertThat(reviews.dismissalRestrictions().users())
				.extracting(SimpleUser::login)
				.containsExactly("alice");
		assertThat(reviews.bypassPullRequestAllowances().teams())
				.extracting(BranchProtectionResponse.Restrictions.Team::slug)
				.containsExactly("platform");
		assertThat(reviews.bypassPullRequestAllowances().apps())
				.extracting(BranchProtectionResponse.Restrictions.App::slug)
				.containsExactly("dependabot");
	}

	@Test
	void pushRestrictionsBecomeTheUsersTeamsAndAppsTriple() {
		BranchProtectionResponse.Restrictions restrictions = protection(
				"""
						"requiresStatusChecks": false, "requiresApprovingReviews": false,
						"restrictsPushes": true,
						"pushAllowances": {"nodes": [
						  {"actor": {"__typename": "User", "login": "alice"}},
						  {"actor": {"__typename": "Team", "slug": "platform"}},
						  {"actor": {"__typename": "App", "slug": "dependabot"}},
						  {"actor": {"__typename": "Mannequin"}}
						]}
						"""
		).restrictions();

		assertThat(restrictions.users()).extracting(SimpleUser::login)
				.containsExactly("alice");
		assertThat(restrictions.teams())
				.extracting(BranchProtectionResponse.Restrictions.Team::slug)
				.containsExactly("platform");
		assertThat(restrictions.apps())
				.extracting(BranchProtectionResponse.Restrictions.App::slug)
				.containsExactly("dependabot");
	}

	// ─── Collaborators
	// ─────────────────────────────────────────────────────

	/**
	 * GraphQL names the level; {@code Permissions.level()} reads the highest
	 * boolean that is on, so only the one named is set.
	 */
	@ParameterizedTest
	@CsvSource(
		{ "ADMIN,admin", "MAINTAIN,maintain", "WRITE,push", "TRIAGE,triage",
				"READ,pull" }
	)
	void everyCollaboratorPermissionBecomesItsLevel(
			String permission,
			String expected
	) {
		CollaboratorResponse collaborator = read(
				GraphQlShape.collaborator(node("""
						{"permission": "%s", "node": {"login": "alice"}}
						""".formatted(permission))),
				CollaboratorResponse.class
		);

		assertThat(collaborator.login()).isEqualTo("alice");
		assertThat(collaborator.permissions().level()).isEqualTo(expected);
	}

	// ─── Reading the fixtures
	// ──────────────────────────────────────────

	private static RulesetDetailsResponse ruleset(String fields) {
		return read(
				GraphQlShape.ruleset(node("{" + fields + "}")),
				RulesetDetailsResponse.class
		);
	}

	private static List<Rule> rules(String rules) {
		return ruleset("""
				"databaseId": 1, "name": "r", "target": "BRANCH",
				"enforcement": "ACTIVE",
				"source": {"__typename": "Repository"},
				"bypassActors": {"nodes": []},
				"rules": {"nodes": [%s]}
				""".formatted(rules)).rules();
	}

	private static BranchProtectionResponse protection(String fields) {
		return read(
				GraphQlShape.branchProtection(node("{" + fields + "}")),
				BranchProtectionResponse.class
		);
	}

	private static JsonNode node(String json) {
		try {
			return MAPPER.readTree(json);
		} catch (Exception e) {
			throw new AssertionError(json, e);
		}
	}

	private static <T> T read(JsonNode rewritten, Class<T> type) {
		try {
			return MAPPER.treeToValue(rewritten, type);
		} catch (Exception e) {
			throw new AssertionError(rewritten.toPrettyString(), e);
		}
	}

}
