package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.pkl.Drifty;

public class RulesetDriftGroup extends DriftGroup {

	private final Map<String, Drifty.Ruleset> desired;
	private final List<ActualRuleset> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public RulesetDriftGroup(
			Map<String, Drifty.Ruleset> desired,
			List<ActualRuleset> actual,
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

		Map<String, ActualRuleset> actualByName = actual.stream()
				.collect(
						Collectors
								.toMap(ActualRuleset::name, r -> r, (a, _) -> a)
				);

		for (var entry : desired.entrySet()) {
			String rName = entry.getKey();
			Drifty.Ruleset wanted = entry.getValue();
			ActualRuleset got = actualByName.get(rName);

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

	private DriftFix deleteExtraFix(ActualRuleset extra) {
		return new DriftFix(new DriftItem.SectionExtra(extra.name()), () -> {
			client.deleteRuleset(owner, repo, extra.id());
			return FixResult.success();
		});
	}

	private List<DriftItem> compareRuleset(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got
	) {
		var items = new ArrayList<DriftItem>();

		ocompare(
				key(rName, ".include_patterns"),
				new HashSet<>(wanted.includePatterns),
				got.includePatterns()
		).ifPresent(items::add);

		ocompare(
				key(rName, ".required_linear_history"),
				wanted.requiredLinearHistory,
				got.requiredLinearHistory()
		).ifPresent(items::add);

		ocompare(
				key(rName, ".no_force_pushes"),
				wanted.noForcePushes,
				got.noForcePushes()
		).ifPresent(items::add);

		compareIfAnyPresent(
				key(rName, ".required_status_checks"),
				desiredStatusChecks(wanted),
				got.requiredStatusChecks(),
				items
		);

		compareRequiredReviewCount(rName, wanted, got, items);

		compareIfAnyPresent(
				key(rName, ".required_code_scanning"),
				wanted.requiredCodeScanning.stream()
						.map(t -> t.tool)
						.collect(Collectors.toSet()),
				got.requiredCodeScanningTools(),
				items
		);

		ocompare(key(rName, ".creation"), wanted.creation, got.creation())
				.ifPresent(items::add);

		ocompare(key(rName, ".deletion"), wanted.deletion, got.deletion())
				.ifPresent(items::add);

		ocompare(
				key(rName, ".required_signatures"),
				wanted.requiredSignatures,
				got.requiredSignatures()
		).ifPresent(items::add);

		ocompare(key(rName, ".update"), wanted.update, got.update())
				.ifPresent(items::add);

		if (wanted.update && got.update()) {
			ocompare(
					key(rName, ".update_allows_fetch_and_merge"),
					wanted.updateAllowsFetchAndMerge,
					got.updateAllowsFetchAndMerge()
			).ifPresent(items::add);
		}

		comparePatternRules(rName, wanted, got, items);

		compareIfAnyPresent(
				key(rName, ".required_deployments"),
				new HashSet<>(wanted.requiredDeployments),
				got.requiredDeployments(),
				items
		);

		compareBypassActors(rName, wanted, got, items);

		return items;
	}

	private void compareRequiredReviewCount(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got,
			List<DriftItem> items
	) {
		if (wanted.requiredReviewCount == null) {
			return;
		}
		Integer wantCount = wanted.requiredReviewCount.intValue();
		if (!wantCount.equals(got.requiredReviewCount())) {
			items.add(
					new DriftItem.FieldMismatch(
							key(rName, ".required_review_count"),
							wantCount,
							got.requiredReviewCount()
					)
			);
		}
	}

	private void comparePatternRules(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got,
			List<DriftItem> items
	) {
		checkPatternRule(
				items,
				rName + ".commit_message_pattern",
				wanted.commitMessagePattern,
				got.commitMessagePattern()
		);
		checkPatternRule(
				items,
				rName + ".commit_author_email_pattern",
				wanted.commitAuthorEmailPattern,
				got.commitAuthorEmailPattern()
		);
		checkPatternRule(
				items,
				rName + ".committer_email_pattern",
				wanted.committerEmailPattern,
				got.committerEmailPattern()
		);
		checkPatternRule(
				items,
				rName + ".branch_name_pattern",
				wanted.branchNamePattern,
				got.branchNamePattern()
		);
		checkPatternRule(
				items,
				rName + ".tag_name_pattern",
				wanted.tagNamePattern,
				got.tagNamePattern()
		);
	}

	private static void compareBypassActors(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got,
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
		Set<String> gotBypass = got.bypassActors()
				.stream()
				.map(ActualRuleset.BypassActor::toString)
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

	private void checkPatternRule(
			List<DriftItem> items,
			String path,
			Drifty.RulePattern wanted,
			String got
	) {
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

}
