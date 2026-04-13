package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.config.CodeScanningToolArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulePatternArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

public class RulesetDriftGroup extends DriftGroup {

	private final List<RulesetArgs> desired;
	private final List<RulesetDetailsResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public RulesetDriftGroup(
			RepositoryArgs desired,
			List<RulesetDetailsResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = List.copyOf(desired.rulesets());
		this.actual = List.copyOf(actual);
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "rulesets";
	}

	@Override
	public List<DriftFix> detect() {
		var fixes = new ArrayList<DriftFix>();

		if (desired.isEmpty() && actual.isEmpty()) {
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

		if (desired.isEmpty()) {
			for (var extra : actual) {
				var item = new DriftItem.SectionExtra(
						"ruleset." + extra.name()
				);
				fixes.add(new DriftFix(List.of(item), () -> {
					client.deleteRuleset(owner, repo, extra.id());
					return FixResult.success();
				}));
			}
			return fixes;
		}

		for (var wanted : desired) {
			String rName = wanted.name();
			RulesetDetailsResponse got = actualByName.get(rName);

			var items = new ArrayList<DriftItem>();

			if (got == null) {
				items.add(new DriftItem.SectionMissing("ruleset." + rName));
				fixes.add(new DriftFix(items, () -> {
					client.createRuleset(
							owner,
							repo,
							buildRulesetRequest(wanted)
					);
					return FixResult.success();
				}));
				continue;
			}

			Set<String> wantIncludes = new HashSet<>(wanted.includePatterns());
			Set<String> gotIncludes = Set.of();
			if (got.conditions() != null && got.conditions().refName() != null
					&& got.conditions().refName().include() != null) {
				gotIncludes = new HashSet<>(
						got.conditions().refName().include()
				);
			}
			ocompare(
					"ruleset." + rName + ".include_patterns",
					wantIncludes,
					gotIncludes
			).ifPresent(items::add);

			Map<RulesetRuleType, Rule> actualRulesByType = buildRulesByType(
					got
			);

			boolean hasLinearHistory = actualRulesByType
					.containsKey(RulesetRuleType.REQUIRED_LINEAR_HISTORY);
			ocompare(
					"ruleset." + rName + ".required_linear_history",
					wanted.requiredLinearHistory(),
					hasLinearHistory
			).ifPresent(items::add);

			boolean hasNonFastForward = actualRulesByType
					.containsKey(RulesetRuleType.NON_FAST_FORWARD);
			ocompare(
					"ruleset." + rName + ".no_force_pushes",
					wanted.noForcePushes(),
					hasNonFastForward
			).ifPresent(items::add);

			Set<StatusCheckArgs> wantChecks = new HashSet<>(
					wanted.requiredStatusChecks()
			);
			Set<StatusCheckArgs> gotChecks = extractStatusChecks(
					actualRulesByType
			);
			if (!wantChecks.isEmpty() || !gotChecks.isEmpty()) {
				ocompare(
						"ruleset." + rName + ".required_status_checks",
						wantChecks,
						gotChecks
				).ifPresent(items::add);
			}

			if (wanted.requiredReviewCount() != null) {
				Integer gotCount = extractRequiredReviewCount(
						actualRulesByType
				);
				if (wanted.requiredReviewCount() != null
						&& !wanted.requiredReviewCount().equals(gotCount)) {
					items.add(
							new DriftItem.FieldMismatch(
									"ruleset." + rName
											+ ".required_review_count",
									wanted.requiredReviewCount(),
									gotCount
							)
					);
				}
			}

			Set<String> wantTools = wanted.requiredCodeScanning()
					.stream()
					.map(CodeScanningToolArgs::tool)
					.collect(Collectors.toSet());
			Set<String> gotTools = extractCodeScanningTools(actualRulesByType);
			if (!wantTools.isEmpty() || !gotTools.isEmpty()) {
				ocompare(
						"ruleset." + rName + ".required_code_scanning",
						wantTools,
						gotTools
				).ifPresent(items::add);
			}

			ocompare(
					"ruleset." + rName + ".creation",
					wanted.creation(),
					actualRulesByType.containsKey(RulesetRuleType.CREATION)
			).ifPresent(items::add);

			ocompare(
					"ruleset." + rName + ".deletion",
					wanted.deletion(),
					actualRulesByType.containsKey(RulesetRuleType.DELETION)
			).ifPresent(items::add);

			ocompare(
					"ruleset." + rName + ".required_signatures",
					wanted.requiredSignatures(),
					actualRulesByType
							.containsKey(RulesetRuleType.REQUIRED_SIGNATURES)
			).ifPresent(items::add);

			ocompare(
					"ruleset." + rName + ".update",
					wanted.update(),
					actualRulesByType.containsKey(RulesetRuleType.UPDATE)
			).ifPresent(items::add);

			if (wanted.update() && actualRulesByType.get(
					RulesetRuleType.UPDATE
			) instanceof Rule.Update updateRule) {
				Boolean gotAllowsFetch = updateRule.parameters() != null
						? updateRule.parameters().updateAllowsFetchAndMerge()
						: null;
				ocompare(
						"ruleset." + rName + ".update_allows_fetch_and_merge",
						wanted.updateAllowsFetchAndMerge(),
						Boolean.TRUE.equals(gotAllowsFetch)
				).ifPresent(items::add);
			}

			checkPatternRule(
					items,
					rName + ".commit_message_pattern",
					wanted.commitMessagePattern(),
					actualRulesByType
							.get(RulesetRuleType.COMMIT_MESSAGE_PATTERN)
			);

			checkPatternRule(
					items,
					rName + ".commit_author_email_pattern",
					wanted.commitAuthorEmailPattern(),
					actualRulesByType
							.get(RulesetRuleType.COMMIT_AUTHOR_EMAIL_PATTERN)
			);

			checkPatternRule(
					items,
					rName + ".committer_email_pattern",
					wanted.committerEmailPattern(),
					actualRulesByType
							.get(RulesetRuleType.COMMITTER_EMAIL_PATTERN)
			);

			checkPatternRule(
					items,
					rName + ".branch_name_pattern",
					wanted.branchNamePattern(),
					actualRulesByType.get(RulesetRuleType.BRANCH_NAME_PATTERN)
			);

			checkPatternRule(
					items,
					rName + ".tag_name_pattern",
					wanted.tagNamePattern(),
					actualRulesByType.get(RulesetRuleType.TAG_NAME_PATTERN)
			);

			Set<String> wantDeployments = wanted.requiredDeployments();
			Set<String> gotDeployments = extractRequiredDeployments(
					actualRulesByType
			);
			if (!wantDeployments.isEmpty() || !gotDeployments.isEmpty()) {
				ocompare(
						"ruleset." + rName + ".required_deployments",
						wantDeployments,
						gotDeployments
				).ifPresent(items::add);
			}

			if (!wanted.bypassActors().isEmpty()) {
				Set<String> wantBypass = wanted.bypassActors()
						.stream()
						.map(
								a -> a.actorType() + ":" + a.actorId() + ":"
										+ a.bypassMode()
						)
						.collect(Collectors.toSet());
				Set<String> gotBypass = got.bypassActors() != null
						? got.bypassActors()
								.stream()
								.map(
										a -> a.actorType() + ":" + a.actorId()
												+ ":" + a.bypassMode()
								)
								.collect(Collectors.toSet())
						: Set.of();
				ocompare(
						"ruleset." + rName + ".bypass_actors",
						wantBypass,
						gotBypass
				).ifPresent(items::add);
			}

			if (!items.isEmpty()) {
				final var gotId = got.id();
				fixes.add(new DriftFix(items, () -> {
					client.updateRuleset(
							owner,
							repo,
							gotId,
							buildRulesetRequest(wanted)
					);
					return FixResult.success();
				}));
			}
		}

		Set<String> desiredNames = desired.stream()
				.map(RulesetArgs::name)
				.collect(Collectors.toSet());
		for (var extra : actual) {
			if (!desiredNames.contains(extra.name())) {
				var item = new DriftItem.SectionExtra(
						"ruleset." + extra.name()
				);
				fixes.add(new DriftFix(List.of(item), () -> {
					client.deleteRuleset(owner, repo, extra.id());
					return FixResult.success();
				}));
			}
		}

		return fixes;
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

	private Set<StatusCheckArgs> extractStatusChecks(
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
							sc -> StatusCheckArgs.builder()
									.context(sc.context())
									.appId(sc.integrationId())
									.build()
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
			RulePatternArgs wanted,
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

		String want = wanted != null ? wanted.pattern() : null;
		if (want != null || got != null) {
			ocompare(path, want, got).ifPresent(items::add);
		}
	}

	private static RulesetRequest buildRulesetRequest(RulesetArgs args) {
		List<Rule> rules = new ArrayList<>();
		if (args.creation()) {
			rules.add(new Rule.Creation());
		}
		if (args.deletion()) {
			rules.add(new Rule.Deletion());
		}
		if (args.requiredSignatures()) {
			rules.add(new Rule.RequiredSignatures());
		}
		if (args.requiredLinearHistory()) {
			rules.add(new Rule.RequiredLinearHistory());
		}
		if (args.noForcePushes()) {
			rules.add(new Rule.NonFastForward());
		}
		if (args.update()) {
			rules.add(
					new Rule.Update(
							new Rule.Update.Parameters(
									args.updateAllowsFetchAndMerge()
							)
					)
			);
		}
		if (!args.requiredStatusChecks().isEmpty()) {
			List<Rule.StatusCheck> checks = args.requiredStatusChecks()
					.stream()
					.map(
							sc -> new Rule.StatusCheck(
									sc.getContext(),
									sc.getAppId()
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
		if (args.requiredReviewCount() != null) {
			rules.add(
					new Rule.PullRequest(
							new Rule.PullRequest.Parameters(
									args.requiredReviewCount(),
									null,
									null,
									null
							)
					)
			);
		}
		if (!args.requiredCodeScanning().isEmpty()) {
			List<Rule.CodeScanningTool> tools = args.requiredCodeScanning()
					.stream()
					.map(
							cst -> new Rule.CodeScanningTool(
									cst.tool(),
									cst.alertsThreshold(),
									cst.securityAlertsThreshold()
							)
					)
					.toList();
			rules.add(
					new Rule.CodeScanning(
							new Rule.CodeScanning.Parameters(tools)
					)
			);
		}
		if (!args.requiredDeployments().isEmpty()) {
			rules.add(
					new Rule.RequiredDeployments(
							new Rule.RequiredDeployments.Parameters(
									new ArrayList<>(args.requiredDeployments())
							)
					)
			);
		}

		if (args.commitMessagePattern() != null) {
			rules.add(
					new Rule.CommitMessagePattern(
							toPatternParameters(args.commitMessagePattern())
					)
			);
		}
		if (args.commitAuthorEmailPattern() != null) {
			rules.add(
					new Rule.CommitAuthorEmailPattern(
							toPatternParameters(args.commitAuthorEmailPattern())
					)
			);
		}
		if (args.committerEmailPattern() != null) {
			rules.add(
					new Rule.CommitterEmailPattern(
							toPatternParameters(args.committerEmailPattern())
					)
			);
		}
		if (args.branchNamePattern() != null) {
			rules.add(
					new Rule.BranchNamePattern(
							toPatternParameters(args.branchNamePattern())
					)
			);
		}
		if (args.tagNamePattern() != null) {
			rules.add(
					new Rule.TagNamePattern(
							toPatternParameters(args.tagNamePattern())
					)
			);
		}

		List<RulesetRequest.BypassActorRequest> bypassActors = args
				.bypassActors()
				.stream()
				.map(
						a -> new RulesetRequest.BypassActorRequest(
								a.actorId(),
								a.actorType(),
								a.bypassMode()
						)
				)
				.toList();
		var refName = new RulesetRequest.Conditions.RefName(
				args.includePatterns(),
				List.of()
		);
		var conditions = new RulesetRequest.Conditions(
				refName,
				null,
				null,
				null
		);
		return new RulesetRequest(
				args.name(),
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				bypassActors,
				conditions,
				rules
		);
	}

	private static Rule.PatternParameters toPatternParameters(
			RulePatternArgs args
	) {
		return new Rule.PatternParameters(
				args.name(),
				args.negate(),
				args.operator(),
				args.pattern()
		);
	}

}
