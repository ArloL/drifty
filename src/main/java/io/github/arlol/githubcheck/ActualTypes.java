package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.SimpleUser;

/**
 * Converts GitHub's REST responses into the {@code actual.*} types the drift
 * comparison works in — the mirror of {@link PklTypes}, which does the same for
 * the Pkl-generated configuration on the desired side.
 * <p>
 * Everything that knows how GitHub serialises a ruleset or a branch protection
 * lives here: the list of typed rule objects with their nested parameters, the
 * {@code {"enabled": bool}} wrappers, the two shapes required status checks
 * come back in, and the sections that are omitted rather than returned empty.
 * Keeping that in one place is what makes a change of read path — GraphQL bulk
 * reads, a new API version — a change to this class rather than to every drift
 * group.
 */
public final class ActualTypes {

	private ActualTypes() {
	}

	// ─── Rulesets
	// ──────────────────────────────────────────────────────────

	public static ActualRuleset ruleset(RulesetDetailsResponse response) {
		Map<RulesetRuleType, Rule> rules = rulesByType(response);
		return new ActualRuleset(
				response.id(),
				response.name(),
				includePatterns(response),
				rules.containsKey(RulesetRuleType.CREATION),
				rules.containsKey(RulesetRuleType.DELETION),
				rules.containsKey(RulesetRuleType.UPDATE),
				updateAllowsFetchAndMerge(rules),
				rules.containsKey(RulesetRuleType.REQUIRED_SIGNATURES),
				rules.containsKey(RulesetRuleType.REQUIRED_LINEAR_HISTORY),
				rules.containsKey(RulesetRuleType.NON_FAST_FORWARD),
				statusChecks(rules),
				requiredReviewCount(rules),
				codeScanningTools(rules),
				requiredDeployments(rules),
				pattern(rules, RulesetRuleType.COMMIT_MESSAGE_PATTERN),
				pattern(rules, RulesetRuleType.COMMIT_AUTHOR_EMAIL_PATTERN),
				pattern(rules, RulesetRuleType.COMMITTER_EMAIL_PATTERN),
				pattern(rules, RulesetRuleType.BRANCH_NAME_PATTERN),
				pattern(rules, RulesetRuleType.TAG_NAME_PATTERN),
				bypassActors(response)
		);
	}

	private static Map<RulesetRuleType, Rule> rulesByType(
			RulesetDetailsResponse ruleset
	) {
		if (ruleset.rules() == null) {
			return Map.of();
		}
		return ruleset.rules()
				.stream()
				.filter(r -> r.type() != null)
				.collect(Collectors.toMap(Rule::type, r -> r, (a, _) -> a));
	}

	private static Set<String> includePatterns(RulesetDetailsResponse ruleset) {
		if (ruleset.conditions() == null
				|| ruleset.conditions().refName() == null
				|| ruleset.conditions().refName().include() == null) {
			return Set.of();
		}
		return new HashSet<>(ruleset.conditions().refName().include());
	}

	private static boolean updateAllowsFetchAndMerge(
			Map<RulesetRuleType, Rule> rules
	) {
		return rules.get(RulesetRuleType.UPDATE) instanceof Rule.Update update
				&& update.parameters() != null
				&& Boolean.TRUE.equals(
						update.parameters().updateAllowsFetchAndMerge()
				);
	}

	private static Set<StatusCheck> statusChecks(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.REQUIRED_STATUS_CHECKS
		) instanceof Rule.RequiredStatusChecks rsc && rsc.parameters() != null
				&& rsc.parameters().requiredStatusChecks() != null) {
			return rsc.parameters()
					.requiredStatusChecks()
					.stream()
					.map(
							sc -> new StatusCheck(
									sc.context(),
									sc.integrationId()
							)
					)
					.collect(Collectors.toSet());
		}
		return Set.of();
	}

	private static Integer requiredReviewCount(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.PULL_REQUEST
		) instanceof Rule.PullRequest pr && pr.parameters() != null) {
			return pr.parameters().requiredApprovingReviewCount();
		}
		return null;
	}

	private static Set<String> codeScanningTools(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.CODE_SCANNING
		) instanceof Rule.CodeScanning cs && cs.parameters() != null
				&& cs.parameters().codeScanningTools() != null) {
			return cs.parameters()
					.codeScanningTools()
					.stream()
					.map(Rule.CodeScanningTool::tool)
					.collect(Collectors.toSet());
		}
		return Set.of();
	}

	private static Set<String> requiredDeployments(
			Map<RulesetRuleType, Rule> rules
	) {
		if (rules.get(
				RulesetRuleType.REQUIRED_DEPLOYMENTS
		) instanceof Rule.RequiredDeployments rd && rd.parameters() != null
				&& rd.parameters().requiredDeploymentEnvironments() != null) {
			return new HashSet<>(
					rd.parameters().requiredDeploymentEnvironments()
			);
		}
		return Set.of();
	}

	private static String pattern(
			Map<RulesetRuleType, Rule> rules,
			RulesetRuleType type
	) {
		return switch (rules.get(type)) {
		case Rule.CommitMessagePattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.CommitAuthorEmailPattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.CommitterEmailPattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.BranchNamePattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case Rule.TagNamePattern r ->
			r.parameters() == null ? null : r.parameters().pattern();
		case null, default -> null;
		};
	}

	private static List<ActualRuleset.BypassActor> bypassActors(
			RulesetDetailsResponse ruleset
	) {
		if (ruleset.bypassActors() == null) {
			return List.of();
		}
		return ruleset.bypassActors()
				.stream()
				.map(
						a -> new ActualRuleset.BypassActor(
								String.valueOf(a.actorType()),
								a.actorId(),
								String.valueOf(a.bypassMode())
						)
				)
				.toList();
	}

	// ─── Branch protection
	// ──────────────────────────────────────────────

	public static ActualBranchProtection branchProtection(
			BranchProtectionResponse response
	) {
		// GitHub omits a section rather than returning it disabled, so every
		// wrapper here is optional.
		return new ActualBranchProtection(
				response.enforceAdmins() != null
						&& response.enforceAdmins().enabled(),
				response.requiredLinearHistory() != null
						&& response.requiredLinearHistory().enabled(),
				response.allowForcePushes() != null
						&& response.allowForcePushes().enabled(),
				response.requiredConversationResolution() != null
						&& response.requiredConversationResolution().enabled(),
				response.requiredStatusChecks() != null
						&& response.requiredStatusChecks().strict(),
				protectionStatusChecks(response),
				pullRequestReviews(response),
				restrictions(response)
		);
	}

	/**
	 * GitHub returns required checks either as {@code checks} objects carrying
	 * an app id, or as bare {@code contexts} strings on older protections.
	 */
	private static Set<StatusCheck> protectionStatusChecks(
			BranchProtectionResponse response
	) {
		var rsc = response.requiredStatusChecks();
		if (rsc == null) {
			return Set.of();
		}
		Set<StatusCheck> checks = new HashSet<>();
		if (rsc.checks() != null && !rsc.checks().isEmpty()) {
			for (var check : rsc.checks()) {
				checks.add(new StatusCheck(check.context(), check.appId()));
			}
		} else if (rsc.contexts() != null) {
			for (var context : rsc.contexts()) {
				checks.add(new StatusCheck(context, null));
			}
		}
		return checks;
	}

	private static Optional<ActualBranchProtection.PullRequestReviews> pullRequestReviews(
			BranchProtectionResponse response
	) {
		var rpr = response.requiredPullRequestReviews();
		if (rpr == null) {
			return Optional.empty();
		}
		return Optional.of(
				new ActualBranchProtection.PullRequestReviews(
						rpr.dismissStaleReviews(),
						rpr.requireCodeOwnerReviews(),
						rpr.requiredApprovingReviewCount(),
						rpr.requireLastPushApproval()
				)
		);
	}

	private static Optional<ActualBranchProtection.Restrictions> restrictions(
			BranchProtectionResponse response
	) {
		var restrictions = response.restrictions();
		if (restrictions == null) {
			return Optional.empty();
		}
		return Optional.of(
				new ActualBranchProtection.Restrictions(
						restrictions.users() == null ? Set.<String>of()
								: restrictions.users()
										.stream()
										.map(SimpleUser::login)
										.collect(Collectors.toSet()),
						restrictions.teams()
								.stream()
								.map(
										BranchProtectionResponse.Restrictions.Team::slug
								)
								.collect(Collectors.toSet()),
						restrictions.apps()
								.stream()
								.map(
										BranchProtectionResponse.Restrictions.App::slug
								)
								.collect(Collectors.toSet())
				)
		);
	}

	static List<ActualRuleset> rulesets(
			List<RulesetDetailsResponse> responses
	) {
		var rulesets = new ArrayList<ActualRuleset>();
		responses.forEach(response -> rulesets.add(ruleset(response)));
		return rulesets;
	}

}
