package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.OrgActionsPermissionsResponse;
import io.github.arlol.githubcheck.client.OrganizationResponse;
import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulePatternOperator;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions;

/**
 * ActualTypes is where all knowledge of GitHub's wire format now lives, so the
 * shapes it has to cope with are worth pinning here rather than being
 * rediscovered through whichever drift group happens to exercise them: rules
 * that carry their value in differently-named nested parameters, sections and
 * toggles GitHub omits instead of returning empty or disabled, the two shapes
 * required status checks come back in, nulls that stand for an empty string,
 * and a wait timer buried in a list of typed protection rules.
 */
class ActualTypesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	// ─── Rulesets
	// ──────────────────────────────────────────────────────────

	@Test
	void readsEachPatternRuleFromItsOwnParameters() {
		ActualRuleset ruleset = ruleset(
				new Rule.CommitMessagePattern(patternParameters("^feat")),
				new Rule.CommitAuthorEmailPattern(
						patternParameters("@example")
				),
				new Rule.CommitterEmailPattern(patternParameters("@corp")),
				new Rule.BranchNamePattern(patternParameters("^release/")),
				new Rule.TagNamePattern(patternParameters("^v"))
		);

		assertThat(ruleset.commitMessagePattern()).isEqualTo("^feat");
		assertThat(ruleset.commitAuthorEmailPattern()).isEqualTo("@example");
		assertThat(ruleset.committerEmailPattern()).isEqualTo("@corp");
		assertThat(ruleset.branchNamePattern()).isEqualTo("^release/");
		assertThat(ruleset.tagNamePattern()).isEqualTo("^v");
	}

	@Test
	void patternRulesAreNullWhenTheRuleIsAbsent() {
		ActualRuleset ruleset = ruleset();

		assertThat(ruleset.commitMessagePattern()).isNull();
		assertThat(ruleset.commitAuthorEmailPattern()).isNull();
		assertThat(ruleset.committerEmailPattern()).isNull();
		assertThat(ruleset.branchNamePattern()).isNull();
		assertThat(ruleset.tagNamePattern()).isNull();
	}

	@Test
	void patternRuleIsNullWhenPresentWithoutParameters() {
		ActualRuleset ruleset = ruleset(new Rule.CommitMessagePattern(null));

		assertThat(ruleset.commitMessagePattern()).isNull();
	}

	@Test
	void readsRequiredDeployments() {
		ActualRuleset ruleset = ruleset(
				new Rule.RequiredDeployments(
						new Rule.RequiredDeployments.Parameters(
								List.of("production", "staging")
						)
				)
		);

		assertThat(ruleset.requiredDeployments())
				.containsExactlyInAnyOrder("production", "staging");
	}

	@Test
	void requiredDeploymentsIsEmptyWhenParametersAreAbsent() {
		assertThat(
				ruleset(new Rule.RequiredDeployments(null))
						.requiredDeployments()
		).isEmpty();
	}

	@Test
	void aRulesetWithNoRulesHasEverythingOff() {
		var response = new RulesetDetailsResponse(
				7L,
				"empty",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);

		ActualRuleset ruleset = ActualTypes.ruleset(response);

		assertThat(ruleset.id()).isEqualTo(7L);
		assertThat(ruleset.name()).isEqualTo("empty");
		assertThat(ruleset.includePatterns()).isEmpty();
		assertThat(ruleset.creation()).isFalse();
		assertThat(ruleset.deletion()).isFalse();
		assertThat(ruleset.update()).isFalse();
		assertThat(ruleset.requiredSignatures()).isFalse();
		assertThat(ruleset.requiredLinearHistory()).isFalse();
		assertThat(ruleset.noForcePushes()).isFalse();
		assertThat(ruleset.requiredStatusChecks()).isEmpty();
		assertThat(ruleset.requiredReviewCount()).isNull();
		assertThat(ruleset.requiredCodeScanningTools()).isEmpty();
		assertThat(ruleset.bypassActors()).isEmpty();
	}

	@Test
	void readsBypassActors() {
		var response = new RulesetDetailsResponse(
				1L,
				"rs",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				List.of(
						new RulesetDetailsResponse.BypassActor(
								5L,
								RulesetDetailsResponse.BypassActor.ActorType.TEAM,
								RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
						)
				),
				null,
				List.of()
		);

		assertThat(ActualTypes.ruleset(response).bypassActors()).singleElement()
				.hasToString("TEAM:5:ALWAYS");
	}

	// ─── Branch protection
	// ──────────────────────────────────────────────

	@Test
	void readsLegacyContextsStyleStatusChecks() {
		var protection = protectionWithStatusChecks(
				new BranchProtectionResponse.RequiredStatusChecks(
						null,
						null,
						false,
						List.of(),
						List.of("build", "test"),
						null
				)
		);

		assertThat(
				ActualTypes.branchProtection(protection).requiredStatusChecks()
		).containsExactlyInAnyOrder(
				new StatusCheck("build", null),
				new StatusCheck("test", null)
		);
	}

	@Test
	void checksStyleStatusChecksWinOverContexts() {
		var protection = protectionWithStatusChecks(
				new BranchProtectionResponse.RequiredStatusChecks(
						null,
						null,
						true,
						List.of(
								new BranchProtectionResponse.RequiredStatusChecks.StatusCheck(
										"build",
										42
								)
						),
						List.of("ignored"),
						null
				)
		);

		var actual = ActualTypes.branchProtection(protection);

		assertThat(actual.requiredStatusChecks())
				.containsExactly(new StatusCheck("build", 42));
		assertThat(actual.strictStatusChecks()).isTrue();
	}

	@Test
	void omittedSectionsReadAsDisabledRatherThanThrowing() {
		var protection = new BranchProtectionResponse(
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"main",
				null,
				null,
				null,
				null
		);

		var actual = ActualTypes.branchProtection(protection);

		assertThat(actual.enforceAdmins()).isFalse();
		assertThat(actual.requiredLinearHistory()).isFalse();
		assertThat(actual.allowForcePushes()).isFalse();
		assertThat(actual.requireConversationResolution()).isFalse();
		assertThat(actual.strictStatusChecks()).isFalse();
		assertThat(actual.requiredStatusChecks()).isEmpty();
		assertThat(actual.pullRequestReviews()).isEmpty();
		assertThat(actual.restrictions()).isEmpty();
	}

	// ─── Repository
	// ──────────────────────────────────────────────────────────

	@Test
	void unsetDescriptionAndHomepageReadAsEmptyStrings() {
		// GitHub sends null for both; the config spells the same thing "".
		ActualRepository repo = repository("""
				{"description": null, "homepage": null, "topics": null}
				""");

		assertThat(repo.description()).isEmpty();
		assertThat(repo.homepage()).isEmpty();
		assertThat(repo.topics()).isEmpty();
	}

	@Test
	void organisationOwnershipComesFromTheOwnerType() {
		assertThat(repository("""
				{"owner": {"login": "acme", "type": "Organization"}}
				""").organizationOwned()).isTrue();
		assertThat(repository("""
				{"owner": {"login": "me", "type": "User"}}
				""").organizationOwned()).isFalse();
		assertThat(repository("{}").organizationOwned()).isFalse();
	}

	@Test
	void readsTheManagedRepositorySettings() {
		ActualRepository repo = repository("""
				{
					"archived": true,
					"description": "A project",
					"homepage": "https://example.com",
					"visibility": "private",
					"default_branch": "trunk",
					"topics": ["java"],
					"has_discussions": true,
					"allow_auto_merge": true,
					"squash_merge_commit_title": "PR_TITLE",
					"merge_commit_message": "BLANK"
				}
				""");

		assertThat(repo.archived()).isTrue();
		assertThat(repo.description()).isEqualTo("A project");
		assertThat(repo.homepage()).isEqualTo("https://example.com");
		assertThat(repo.visibility()).isEqualTo(RepositoryVisibility.PRIVATE);
		assertThat(repo.defaultBranch()).isEqualTo("trunk");
		assertThat(repo.topics()).containsExactly("java");
		assertThat(repo.hasDiscussions()).isTrue();
		assertThat(repo.allowAutoMerge()).isTrue();
		assertThat(repo.squashMergeCommitTitle())
				.isEqualTo(SquashMergeCommitTitle.PR_TITLE);
		assertThat(repo.mergeCommitMessage())
				.isEqualTo(MergeCommitMessage.BLANK);
	}

	// ─── Security and analysis
	// ──────────────────────────────────────────────────────────

	@Test
	void aMissingSecurityBlockReadsAsEverythingOff() {
		ActualSecurityAndAnalysis security = securityAndAnalysis("{}");

		assertThat(security.secretScanning()).isFalse();
		assertThat(security.secretScanningPushProtection()).isFalse();
		assertThat(security.secretScanningNonProviderPatterns()).isFalse();
		assertThat(security.secretScanningValidityChecks()).isFalse();
		assertThat(security.advancedSecurity()).isFalse();
		assertThat(security.secretScanningAiDetection()).isFalse();
		assertThat(security.secretScanningDelegatedAlertDismissal()).isFalse();
		assertThat(security.secretScanningDelegatedBypass()).isFalse();
		assertThat(security.bypassReviewers()).isEmpty();
	}

	@Test
	void anOmittedToggleReadsAsOffNextToAnEnabledOne() {
		ActualSecurityAndAnalysis security = securityAndAnalysis("""
				{
					"security_and_analysis": {
						"secret_scanning": {"status": "enabled"},
						"advanced_security": {"status": "disabled"}
					}
				}
				""");

		assertThat(security.secretScanning()).isTrue();
		assertThat(security.advancedSecurity()).isFalse();
		assertThat(security.secretScanningPushProtection()).isFalse();
	}

	@Test
	void readsDelegatedBypassReviewers() {
		ActualSecurityAndAnalysis security = securityAndAnalysis(
				"""
						{
							"security_and_analysis": {
								"secret_scanning_delegated_bypass": {"status": "enabled"},
								"secret_scanning_delegated_bypass_options": {
									"reviewers": [
										{"reviewer_id": 7, "reviewer_type": "TEAM"},
										{"reviewer_id": 9, "reviewer_type": "ROLE"}
									]
								}
							}
						}
						"""
		);

		assertThat(security.secretScanningDelegatedBypass()).isTrue();
		assertThat(security.bypassReviewers()).containsExactly(
				new ActualSecurityAndAnalysis.BypassReviewer("TEAM", 7),
				new ActualSecurityAndAnalysis.BypassReviewer("ROLE", 9)
		);
	}

	// ─── Pages
	// ──────────────────────────────────────────────────────────

	@Test
	void pagesBuildTypeIsTheConfigsSpellingAndSourceIsOptional() {
		ActualPages legacy = ActualTypes.pages(
				pages(
						PagesBuildType.LEGACY,
						new PagesResponse.Source("gh-pages", "/docs"),
						true
				)
		);
		ActualPages workflow = ActualTypes
				.pages(pages(PagesBuildType.WORKFLOW, null, false));

		assertThat(legacy.buildType()).isEqualTo("legacy");
		assertThat(legacy.source())
				.contains(new ActualPages.Source("gh-pages", "/docs"));
		assertThat(legacy.httpsEnforced()).isTrue();
		assertThat(workflow.buildType()).isEqualTo("workflow");
		assertThat(workflow.source()).isEmpty();
		assertThat(workflow.httpsEnforced()).isFalse();
	}

	@Test
	void aSiteThatPredatesBuildTypesHasNone() {
		assertThat(ActualTypes.pages(pages(null, null, true)).buildType())
				.isNull();
	}

	// ─── Environments
	// ──────────────────────────────────────────────────────────

	@Test
	void readsTheWaitTimerFromItsProtectionRule() {
		var response = new EnvironmentDetailsResponse(
				"production",
				List.of(
						new EnvironmentDetailsResponse.ProtectionRule(
								EnvironmentDetailsResponse.ProtectionRuleType.REQUIRED_REVIEWERS,
								null,
								List.of()
						),
						new EnvironmentDetailsResponse.ProtectionRule(
								EnvironmentDetailsResponse.ProtectionRuleType.WAIT_TIMER,
								30,
								null
						)
				),
				new EnvironmentDetailsResponse.DeploymentBranchPolicy(
						true,
						false
				)
		);

		ActualEnvironment environment = ActualTypes.environment(response);

		assertThat(environment.waitTimer()).isEqualTo(30);
		assertThat(environment.protectedBranches()).isTrue();
		assertThat(environment.customBranchPolicies()).isFalse();
	}

	@Test
	void anUnprotectedEnvironmentHasNoWaitAndNoPolicy() {
		ActualEnvironment environment = ActualTypes.environment(
				new EnvironmentDetailsResponse("production", null, null)
		);

		assertThat(environment.waitTimer()).isZero();
		assertThat(environment.protectedBranches()).isFalse();
		assertThat(environment.customBranchPolicies()).isFalse();
	}

	// ─── Secrets and workflow permissions
	// ──────────────────────────────────────────────────────────

	@Test
	void secretsKeepTheirNameAndUpdateTimestamp() {
		var secret = ActualTypes.secret(
				new Secret(
						"TOKEN",
						"2023-01-01T00:00:00Z",
						"2024-01-01T00:00:00Z"
				)
		);

		assertThat(secret.name()).isEqualTo("TOKEN");
		assertThat(secret.updatedAt()).isEqualTo("2024-01-01T00:00:00Z");
	}

	@Test
	void readsWorkflowPermissions() {
		var permissions = ActualTypes.workflowPermissions(
				new WorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.READ,
						true
				)
		);

		assertThat(permissions.defaultWorkflowPermissions())
				.isEqualTo(WorkflowPermissions.DefaultWorkflowPermissions.READ);
		assertThat(permissions.canApprovePullRequestReviews()).isTrue();
	}

	// ─── Fixtures
	// ──────────────────────────────────────────────────────────

	private static ActualRepository repository(String detailsJson) {
		return ActualTypes.repository(details(detailsJson));
	}

	private static ActualSecurityAndAnalysis securityAndAnalysis(
			String detailsJson
	) {
		return ActualTypes.securityAndAnalysis(details(detailsJson));
	}

	private static RepositoryDetailsResponse details(String json) {
		try {
			return MAPPER.readValue(json, RepositoryDetailsResponse.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static PagesResponse pages(
			PagesBuildType buildType,
			PagesResponse.Source source,
			boolean httpsEnforced
	) {
		return new PagesResponse(
				null,
				PagesResponse.Status.BUILT,
				null,
				false,
				null,
				buildType,
				source,
				true,
				null,
				null,
				null,
				httpsEnforced
		);
	}

	private static ActualRuleset ruleset(Rule... rules) {
		return ActualTypes.ruleset(
				new RulesetDetailsResponse(
						1L,
						"rs",
						RulesetTarget.BRANCH,
						RulesetEnforcement.ACTIVE,
						null,
						null,
						null,
						null,
						null,
						null,
						List.of(),
						null,
						List.of(rules)
				)
		);
	}

	private static Rule.PatternParameters patternParameters(String pattern) {
		return new Rule.PatternParameters(
				null,
				false,
				RulePatternOperator.STARTS_WITH,
				pattern
		);
	}

	private static BranchProtectionResponse protectionWithStatusChecks(
			BranchProtectionResponse.RequiredStatusChecks checks
	) {
		return new BranchProtectionResponse(
				null,
				null,
				new BranchProtectionResponse.EnforceAdmins(null, false),
				new BranchProtectionResponse.RequiredLinearHistory(false),
				new BranchProtectionResponse.AllowForcePushes(false),
				null,
				null,
				null,
				checks,
				null,
				null,
				"main",
				null,
				null,
				null,
				null
		);
	}

	@Test
	void organization_normalisesNullsAndMissingFlags() {
		var response = new OrganizationResponse(
				"my-org",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"read",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"main",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null
		);

		ActualOrganization actual = ActualTypes.organization(response);

		assertThat(actual.description()).isEmpty();
		assertThat(actual.displayName()).isEmpty();
		assertThat(actual.websiteUrl()).isEmpty();
		assertThat(actual.membersCanCreatePages()).isFalse();
		assertThat(actual.defaultRepositoryBranch()).isEqualTo("main");
	}

	@Test
	void orgActionsPermissions_keepsSelectedActionsOnlyWhenSelected() {
		var response = new OrgActionsPermissionsResponse(
				ActionsEnabledRepositories.ALL,
				AllowedActions.ALL,
				false
		);

		ActualOrgActionsPermissions actual = ActualTypes
				.orgActionsPermissions(response, null);

		assertThat(actual.selectedActions()).isNull();
		assertThat(actual.shaPinningRequired()).isFalse();
	}

}
