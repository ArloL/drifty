package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.pkl.Drifty;

public class RulesetDriftGroup extends DriftGroup {

	private final Map<String, Drifty.Ruleset> desired;
	private final List<RulesetDetailsResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public RulesetDriftGroup(
			Map<String, Drifty.Ruleset> desired,
			List<RulesetDetailsResponse> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = Map.copyOf(desired);
		this.actual = List.copyOf(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public String name() {
		return "rulesets";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		if (desired.isEmpty() && actual.isEmpty()) {
			return fixes;
		}

		if (desired.isEmpty()) {
			actual.stream().map(this::deleteExtraFix).forEach(fixes::add);
			return fixes;
		}

		Map<String, RulesetDetailsResponse> actualByName = actual.stream()
				.collect(
						Collectors.toMap(
								RulesetDetailsResponse::name,
								r -> r,
								(a, _) -> a
						)
				);

		for (var entry : desired.entrySet()) {
			String rName = entry.getKey();
			Drifty.Ruleset wanted = entry.getValue();
			RulesetDetailsResponse got = actualByName.get(rName);

			if (got == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(rName),
								() -> {
									client.createRuleset(
											owner,
											repo,
											buildRulesetRequest(rName, wanted)
									);
									return FixResult.success();
								}
						)
				);
				continue;
			}

			var items = compareRuleset(rName, wanted, got);
			if (!items.isEmpty()) {
				final var gotId = got.id();
				fixes.add(new DriftFix(items, () -> {
					client.updateRuleset(
							owner,
							repo,
							gotId,
							buildRulesetRequest(rName, wanted)
					);
					return FixResult.success();
				}));
			}
		}

		actual.stream()
				.filter(extra -> !desired.containsKey(extra.name()))
				.map(this::deleteExtraFix)
				.forEach(fixes::add);

		return fixes;
	}

	private DriftFix deleteExtraFix(RulesetDetailsResponse extra) {
		return new DriftFix(new DriftItem.SectionExtra(extra.name()), () -> {
			client.deleteRuleset(owner, repo, extra.id());
			return FixResult.success();
		});
	}

	private List<DriftItem> compareRuleset(
			String rName,
			Drifty.Ruleset wanted,
			RulesetDetailsResponse got
	) {
		var items = new ArrayList<DriftItem>();
		Map<RulesetRuleType, Rule> rulesByType = buildRulesByType(got);

		compareIncludePatterns(rName, wanted, got, items);

		ocompare(
				key(rName, ".required_linear_history"),
				wanted.requiredLinearHistory,
				rulesByType.containsKey(RulesetRuleType.REQUIRED_LINEAR_HISTORY)
		).ifPresent(items::add);

		ocompare(
				key(rName, ".no_force_pushes"),
				wanted.noForcePushes,
				rulesByType.containsKey(RulesetRuleType.NON_FAST_FORWARD)
		).ifPresent(items::add);

		compareIfAnyPresent(
				key(rName, ".required_status_checks"),
				desiredStatusChecks(wanted),
				extractStatusChecks(rulesByType),
				items
		);

		compareRequiredReviewCount(rName, wanted, rulesByType, items);

		compareIfAnyPresent(
				key(rName, ".required_code_scanning"),
				wanted.requiredCodeScanning.stream()
						.map(t -> t.tool)
						.collect(Collectors.toSet()),
				extractCodeScanningTools(rulesByType),
				items
		);

		ocompare(
				key(rName, ".creation"),
				wanted.creation,
				rulesByType.containsKey(RulesetRuleType.CREATION)
		).ifPresent(items::add);

		ocompare(
				key(rName, ".deletion"),
				wanted.deletion,
				rulesByType.containsKey(RulesetRuleType.DELETION)
		).ifPresent(items::add);

		ocompare(
				key(rName, ".required_signatures"),
				wanted.requiredSignatures,
				rulesByType.containsKey(RulesetRuleType.REQUIRED_SIGNATURES)
		).ifPresent(items::add);

		ocompare(
				key(rName, ".update"),
				wanted.update,
				rulesByType.containsKey(RulesetRuleType.UPDATE)
		).ifPresent(items::add);

		compareUpdateAllowsFetchAndMerge(rName, wanted, rulesByType, items);
		comparePatternRules(rName, wanted, rulesByType, items);

		compareIfAnyPresent(
				key(rName, ".required_deployments"),
				new HashSet<>(wanted.requiredDeployments),
				extractRequiredDeployments(rulesByType),
				items
		);

		compareBypassActors(rName, wanted, got, items);

		return items;
	}

	private static void compareIncludePatterns(
			String rName,
			Drifty.Ruleset wanted,
			RulesetDetailsResponse got,
			List<DriftItem> items
	) {
		Set<String> gotIncludes = Set.of();
		if (got.conditions() != null && got.conditions().refName() != null
				&& got.conditions().refName().include() != null) {
			gotIncludes = new HashSet<>(got.conditions().refName().include());
		}
		ocompare(
				key(rName, ".include_patterns"),
				new HashSet<>(wanted.includePatterns),
				gotIncludes
		).ifPresent(items::add);
	}

	private void compareRequiredReviewCount(
			String rName,
			Drifty.Ruleset wanted,
			Map<RulesetRuleType, Rule> rulesByType,
			List<DriftItem> items
	) {
		if (wanted.requiredReviewCount == null) {
			return;
		}
		Integer gotCount = extractRequiredReviewCount(rulesByType);
		Integer wantCount = wanted.requiredReviewCount.intValue();
		if (!wantCount.equals(gotCount)) {
			items.add(
					new DriftItem.FieldMismatch(
							key(rName, ".required_review_count"),
							wantCount,
							gotCount
					)
			);
		}
	}

	private static void compareUpdateAllowsFetchAndMerge(
			String rName,
			Drifty.Ruleset wanted,
			Map<RulesetRuleType, Rule> rulesByType,
			List<DriftItem> items
	) {
		if (!wanted.update || !(rulesByType.get(
				RulesetRuleType.UPDATE
		) instanceof Rule.Update updateRule)) {
			return;
		}
		Boolean gotAllowsFetch = updateRule.parameters() != null
				? updateRule.parameters().updateAllowsFetchAndMerge()
				: null;
		ocompare(
				key(rName, ".update_allows_fetch_and_merge"),
				wanted.updateAllowsFetchAndMerge,
				Boolean.TRUE.equals(gotAllowsFetch)
		).ifPresent(items::add);
	}

	private void comparePatternRules(
			String rName,
			Drifty.Ruleset wanted,
			Map<RulesetRuleType, Rule> rulesByType,
			List<DriftItem> items
	) {
		checkPatternRule(
				items,
				rName + ".commit_message_pattern",
				wanted.commitMessagePattern,
				rulesByType.get(RulesetRuleType.COMMIT_MESSAGE_PATTERN)
		);

		checkPatternRule(
				items,
				rName + ".commit_author_email_pattern",
				wanted.commitAuthorEmailPattern,
				rulesByType.get(RulesetRuleType.COMMIT_AUTHOR_EMAIL_PATTERN)
		);

		checkPatternRule(
				items,
				rName + ".committer_email_pattern",
				wanted.committerEmailPattern,
				rulesByType.get(RulesetRuleType.COMMITTER_EMAIL_PATTERN)
		);

		checkPatternRule(
				items,
				rName + ".branch_name_pattern",
				wanted.branchNamePattern,
				rulesByType.get(RulesetRuleType.BRANCH_NAME_PATTERN)
		);

		checkPatternRule(
				items,
				rName + ".tag_name_pattern",
				wanted.tagNamePattern,
				rulesByType.get(RulesetRuleType.TAG_NAME_PATTERN)
		);
	}

	private static void compareBypassActors(
			String rName,
			Drifty.Ruleset wanted,
			RulesetDetailsResponse got,
			List<DriftItem> items
	) {
		if (wanted.bypassActors.isEmpty()) {
			return;
		}
		Set<String> wantBypass = wanted.bypassActors.stream()
				.map(
						a -> PklTypes.actorType(a.actorType) + ":" + a.actorId
								+ ":" + PklTypes.bypassMode(a.bypassMode)
				)
				.collect(Collectors.toSet());
		Set<String> gotBypass = got.bypassActors() == null ? Set.of()
				: got.bypassActors()
						.stream()
						.map(
								a -> a.actorType() + ":" + a.actorId() + ":"
										+ a.bypassMode()
						)
						.collect(Collectors.toSet());
		ocompare(key(rName, ".bypass_actors"), wantBypass, gotBypass)
				.ifPresent(items::add);
	}

	private static <T> void compareIfAnyPresent(
			String path,
			Set<T> wanted,
			Set<T> got,
			List<DriftItem> items
	) {
		if (!wanted.isEmpty() || !got.isEmpty()) {
			ocompare(path, wanted, got).ifPresent(items::add);
		}
	}

	private static String key(String rName, String suffix) {
		return rName + suffix;
	}

	private Map<RulesetRuleType, Rule> buildRulesByType(
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

	private static Set<StatusCheck> desiredStatusChecks(Drifty.Ruleset r) {
		Set<StatusCheck> checks = new HashSet<>();
		for (var sc : r.requiredStatusChecks) {
			checks.add(
					new StatusCheck(
							sc.context,
							sc.appId != null ? sc.appId.intValue() : null
					)
			);
		}
		return checks;
	}

	private Set<StatusCheck> extractStatusChecks(
			Map<RulesetRuleType, Rule> rulesByType
	) {
		if (rulesByType.get(
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

	private Integer extractRequiredReviewCount(
			Map<RulesetRuleType, Rule> rulesByType
	) {
		if (rulesByType.get(
				RulesetRuleType.PULL_REQUEST
		) instanceof Rule.PullRequest pr && pr.parameters() != null) {
			return pr.parameters().requiredApprovingReviewCount();
		}
		return null;
	}

	private Set<String> extractCodeScanningTools(
			Map<RulesetRuleType, Rule> rulesByType
	) {
		if (rulesByType.get(
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

	private Set<String> extractRequiredDeployments(
			Map<RulesetRuleType, Rule> rulesByType
	) {
		if (rulesByType.get(
				RulesetRuleType.REQUIRED_DEPLOYMENTS
		) instanceof Rule.RequiredDeployments rd && rd.parameters() != null
				&& rd.parameters().requiredDeploymentEnvironments() != null) {
			return new HashSet<>(
					rd.parameters().requiredDeploymentEnvironments()
			);
		}
		return Set.of();
	}

	private void checkPatternRule(
			List<DriftItem> items,
			String path,
			Drifty.RulePattern wanted,
			Rule actual
	) {
		String got = null;
		if (actual instanceof Rule.CommitMessagePattern cmp
				&& cmp.parameters() != null) {
			got = cmp.parameters().pattern();
		} else if (actual instanceof Rule.CommitAuthorEmailPattern caep
				&& caep.parameters() != null) {
			got = caep.parameters().pattern();
		} else if (actual instanceof Rule.CommitterEmailPattern cep
				&& cep.parameters() != null) {
			got = cep.parameters().pattern();
		} else if (actual instanceof Rule.BranchNamePattern bnp
				&& bnp.parameters() != null) {
			got = bnp.parameters().pattern();
		} else if (actual instanceof Rule.TagNamePattern tnp
				&& tnp.parameters() != null) {
			got = tnp.parameters().pattern();
		}

		String want = wanted != null ? wanted.pattern : null;
		if (want != null || got != null) {
			ocompare(path, want, got).ifPresent(items::add);
		}
	}

	private static RulesetRequest buildRulesetRequest(
			String name,
			Drifty.Ruleset args
	) {
		List<Rule> rules = new ArrayList<>();
		addBooleanRules(args, rules);
		addParameterizedRules(args, rules);
		addPatternRules(args, rules);

		List<RulesetRequest.BypassActorRequest> bypassActors = args.bypassActors
				.stream()
				.map(
						a -> new RulesetRequest.BypassActorRequest(
								a.actorId,
								PklTypes.actorType(a.actorType),
								PklTypes.bypassMode(a.bypassMode)
						)
				)
				.toList();
		var refName = new RulesetRequest.Conditions.RefName(
				args.includePatterns,
				List.of()
		);
		var conditions = new RulesetRequest.Conditions(
				refName,
				null,
				null,
				null
		);
		return new RulesetRequest(
				name,
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				bypassActors,
				conditions,
				rules
		);
	}

	private static void addBooleanRules(Drifty.Ruleset args, List<Rule> rules) {
		if (args.creation) {
			rules.add(new Rule.Creation());
		}
		if (args.deletion) {
			rules.add(new Rule.Deletion());
		}
		if (args.requiredSignatures) {
			rules.add(new Rule.RequiredSignatures());
		}
		if (args.requiredLinearHistory) {
			rules.add(new Rule.RequiredLinearHistory());
		}
		if (args.noForcePushes) {
			rules.add(new Rule.NonFastForward());
		}
		if (args.update) {
			rules.add(
					new Rule.Update(
							new Rule.Update.Parameters(
									args.updateAllowsFetchAndMerge
							)
					)
			);
		}
	}

	private static void addParameterizedRules(
			Drifty.Ruleset args,
			List<Rule> rules
	) {
		if (!args.requiredStatusChecks.isEmpty()) {
			List<Rule.StatusCheck> checks = args.requiredStatusChecks.stream()
					.map(
							sc -> new Rule.StatusCheck(
									sc.context,
									sc.appId != null ? sc.appId.intValue()
											: null
							)
					)
					.toList();
			rules.add(
					new Rule.RequiredStatusChecks(
							new Rule.RequiredStatusChecks.Parameters(
									checks,
									false
							)
					)
			);
		}
		if (args.requiredReviewCount != null) {
			rules.add(
					new Rule.PullRequest(
							new Rule.PullRequest.Parameters(
									args.requiredReviewCount.intValue(),
									null,
									null,
									null
							)
					)
			);
		}
		if (!args.requiredCodeScanning.isEmpty()) {
			List<Rule.CodeScanningTool> tools = args.requiredCodeScanning
					.stream()
					.map(
							cst -> new Rule.CodeScanningTool(
									cst.tool,
									PklTypes.alertsThreshold(
											cst.alertsThreshold
									),
									PklTypes.securityAlertsThreshold(
											cst.securityAlertsThreshold
									)
							)
					)
					.toList();
			rules.add(
					new Rule.CodeScanning(
							new Rule.CodeScanning.Parameters(tools)
					)
			);
		}
		if (!args.requiredDeployments.isEmpty()) {
			rules.add(
					new Rule.RequiredDeployments(
							new Rule.RequiredDeployments.Parameters(
									new ArrayList<>(args.requiredDeployments)
							)
					)
			);
		}
	}

	private static void addPatternRules(Drifty.Ruleset args, List<Rule> rules) {
		if (args.commitMessagePattern != null) {
			rules.add(
					new Rule.CommitMessagePattern(
							toPatternParameters(args.commitMessagePattern)
					)
			);
		}
		if (args.commitAuthorEmailPattern != null) {
			rules.add(
					new Rule.CommitAuthorEmailPattern(
							toPatternParameters(args.commitAuthorEmailPattern)
					)
			);
		}
		if (args.committerEmailPattern != null) {
			rules.add(
					new Rule.CommitterEmailPattern(
							toPatternParameters(args.committerEmailPattern)
					)
			);
		}
		if (args.branchNamePattern != null) {
			rules.add(
					new Rule.BranchNamePattern(
							toPatternParameters(args.branchNamePattern)
					)
			);
		}
		if (args.tagNamePattern != null) {
			rules.add(
					new Rule.TagNamePattern(
							toPatternParameters(args.tagNamePattern)
					)
			);
		}
	}

	private static Rule.PatternParameters toPatternParameters(
			Drifty.RulePattern args
	) {
		return new Rule.PatternParameters(
				args.name,
				args.negate,
				PklTypes.patternOperator(args.operator),
				args.pattern
		);
	}

	private record StatusCheck(
			String context,
			Integer appId
	) {

		@Override
		public String toString() {
			return appId != null ? context + ":" + appId : context;
		}

	}

}
