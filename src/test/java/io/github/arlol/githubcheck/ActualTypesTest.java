package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulePatternOperator;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;

/**
 * ActualTypes is where all knowledge of GitHub's wire format now lives, so the
 * shapes it has to cope with are worth pinning here rather than being
 * rediscovered through whichever drift group happens to exercise them: rules
 * that carry their value in differently-named nested parameters, sections
 * GitHub omits instead of returning empty, and the two shapes required status
 * checks come back in.
 */
class ActualTypesTest {

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

	// ─── Fixtures
	// ──────────────────────────────────────────────────────────

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

}
