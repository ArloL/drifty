package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The comparison and request building a repository ruleset and an organization
 * ruleset have in common — everything but the endpoint and the repository
 * conditions only an organization ruleset carries.
 * <p>
 * Paths are relative to the ruleset's name; the group that calls this prefixes
 * them. A rule that is absent on both sides is not compared, so a config that
 * does not mention a rule type says nothing about it.
 */
final class RulesetComparison {

	private RulesetComparison() {
	}

	static List<DriftItem> compare(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got
	) {
		var items = new ArrayList<DriftItem>();

		DriftGroup
				.ocompare(
						key(rName, ".target"),
						wanted.target.toString(),
						got.target()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".enforcement"),
						wanted.enforcement.toString(),
						got.enforcement()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".include_patterns"),
						new HashSet<>(wanted.includePatterns),
						got.includePatterns()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".exclude_patterns"),
						new HashSet<>(wanted.excludePatterns),
						got.excludePatterns()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".required_linear_history"),
						wanted.requiredLinearHistory,
						got.requiredLinearHistory()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".no_force_pushes"),
						wanted.noForcePushes,
						got.noForcePushes()
				)
				.ifPresent(items::add);

		compareStatusChecks(rName, wanted, got, items);
		comparePullRequest(rName, wanted, got, items);

		compareIfAnyPresent(
				key(rName, ".required_code_scanning"),
				wanted.requiredCodeScanning.stream()
						.map(t -> t.tool)
						.collect(Collectors.toSet()),
				got.requiredCodeScanningTools(),
				items
		);

		DriftGroup
				.ocompare(
						key(rName, ".creation"),
						wanted.creation,
						got.creation()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".deletion"),
						wanted.deletion,
						got.deletion()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".required_signatures"),
						wanted.requiredSignatures,
						got.requiredSignatures()
				)
				.ifPresent(items::add);
		DriftGroup.ocompare(key(rName, ".update"), wanted.update, got.update())
				.ifPresent(items::add);
		if (wanted.update && got.update()) {
			DriftGroup
					.ocompare(
							key(rName, ".update_allows_fetch_and_merge"),
							wanted.updateAllowsFetchAndMerge,
							got.updateAllowsFetchAndMerge()
					)
					.ifPresent(items::add);
		}

		comparePatternRules(rName, wanted, got, items);

		compareIfAnyPresent(
				key(rName, ".required_deployments"),
				new HashSet<>(wanted.requiredDeployments),
				got.requiredDeployments(),
				items
		);

		compareMergeQueue(rName, wanted, got, items);
		compareIfAnyPresent(
				key(rName, ".workflows"),
				wanted.workflows.stream()
						.map(RulesetComparison::workflow)
						.map(Object::toString)
						.collect(Collectors.toSet()),
				got.workflows()
						.stream()
						.map(Object::toString)
						.collect(Collectors.toSet()),
				items
		);
		compareIfAnyPresent(
				key(rName, ".file_path_restrictions"),
				new HashSet<>(wanted.filePathRestrictions),
				got.filePathRestrictions(),
				items
		);
		compareIfAnyPresent(
				key(rName, ".file_extension_restrictions"),
				new HashSet<>(wanted.fileExtensionRestrictions),
				got.fileExtensionRestrictions(),
				items
		);
		compareOptionalInt(
				key(rName, ".max_file_path_length"),
				wanted.maxFilePathLength,
				got.maxFilePathLength(),
				items
		);
		compareOptionalInt(
				key(rName, ".max_file_size"),
				wanted.maxFileSize,
				got.maxFileSize(),
				items
		);

		compareBypassActors(rName, wanted, got, items);

		return items;
	}

	private static void compareStatusChecks(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got,
			List<DriftItem> items
	) {
		Set<StatusCheck> wantChecks = desiredStatusChecks(wanted);
		if (wantChecks.isEmpty() && got.requiredStatusChecks().isEmpty()) {
			return;
		}
		DriftGroup
				.ocompare(
						key(rName, ".required_status_checks"),
						wantChecks,
						got.requiredStatusChecks()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						key(rName, ".required_status_checks.strict"),
						wanted.strictRequiredStatusChecks,
						got.strictRequiredStatusChecks()
				)
				.ifPresent(items::add);
	}

	/**
	 * The rule's presence is compared first: a config that sets no
	 * {@code pullRequest} wants direct pushes allowed, so a rule GitHub has is
	 * extra, and one the config has that GitHub lacks is missing. Only when
	 * both sides have the rule are its parameters compared.
	 */
	private static void comparePullRequest(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got,
			List<DriftItem> items
	) {
		String prefix = key(rName, ".pull_request");
		var want = wanted.pullRequest;
		var have = got.pullRequest();
		if (want == null && have == null) {
			return;
		}
		if (want == null) {
			items.add(new DriftItem.SectionExtra(prefix));
			return;
		}
		if (have == null) {
			items.add(new DriftItem.SectionMissing(prefix));
			return;
		}
		DriftGroup
				.ocompare(
						prefix + ".required_approving_review_count",
						(int) want.requiredApprovingReviewCount,
						have.requiredApprovingReviewCount()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".dismiss_stale_reviews_on_push",
						want.dismissStaleReviewsOnPush,
						have.dismissStaleReviewsOnPush()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".require_code_owner_review",
						want.requireCodeOwnerReview,
						have.requireCodeOwnerReview()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".require_last_push_approval",
						want.requireLastPushApproval,
						have.requireLastPushApproval()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".required_review_thread_resolution",
						want.requiredReviewThreadResolution,
						have.requiredReviewThreadResolution()
				)
				.ifPresent(items::add);
		// An empty list means "every method", which GitHub spells out as all
		// three; only a config that restricts them has something to compare.
		if (!want.allowedMergeMethods.isEmpty()) {
			DriftGroup
					.ocompare(
							prefix + ".allowed_merge_methods",
							want.allowedMergeMethods.stream()
									.map(Object::toString)
									.collect(Collectors.toSet()),
							have.allowedMergeMethods()
					)
					.ifPresent(items::add);
		}
	}

	private static void compareMergeQueue(
			String rName,
			Drifty.Ruleset wanted,
			ActualRuleset got,
			List<DriftItem> items
	) {
		String prefix = key(rName, ".merge_queue");
		var want = wanted.mergeQueue;
		var have = got.mergeQueue();
		if (want == null && have == null) {
			return;
		}
		if (want == null) {
			items.add(new DriftItem.SectionExtra(prefix));
			return;
		}
		if (have == null) {
			items.add(new DriftItem.SectionMissing(prefix));
			return;
		}
		DriftGroup
				.ocompare(
						prefix + ".check_response_timeout_minutes",
						(int) want.checkResponseTimeoutMinutes,
						have.checkResponseTimeoutMinutes()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".grouping_strategy",
						want.groupingStrategy.toString(),
						have.groupingStrategy()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".max_entries_to_build",
						(int) want.maxEntriesToBuild,
						have.maxEntriesToBuild()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".max_entries_to_merge",
						(int) want.maxEntriesToMerge,
						have.maxEntriesToMerge()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".merge_method",
						want.mergeMethod.toString(),
						have.mergeMethod()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".min_entries_to_merge",
						(int) want.minEntriesToMerge,
						have.minEntriesToMerge()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						prefix + ".min_entries_to_merge_wait_minutes",
						(int) want.minEntriesToMergeWaitMinutes,
						have.minEntriesToMergeWaitMinutes()
				)
				.ifPresent(items::add);
	}

	private static void comparePatternRules(
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
		DriftGroup.ocompare(key(rName, ".bypass_actors"), wantBypass, gotBypass)
				.ifPresent(items::add);
	}

	static <T> void compareIfAnyPresent(
			String path,
			Set<T> wanted,
			Set<T> got,
			List<DriftItem> items
	) {
		if (!wanted.isEmpty() || !got.isEmpty()) {
			DriftGroup.ocompare(path, wanted, got).ifPresent(items::add);
		}
	}

	private static void compareOptionalInt(
			String path,
			Long wanted,
			Integer got,
			List<DriftItem> items
	) {
		Integer want = wanted == null ? null : wanted.intValue();
		if ((want != null || got != null) && !Objects.equals(want, got)) {
			items.add(new DriftItem.FieldMismatch(path, want, got));
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

	private static ActualRuleset.Workflow workflow(Drifty.WorkflowRule w) {
		return new ActualRuleset.Workflow(w.path, w.repositoryId, w.ref);
	}

	private static void checkPatternRule(
			List<DriftItem> items,
			String path,
			Drifty.RulePattern wanted,
			String got
	) {
		String want = wanted != null ? wanted.pattern : null;
		if (want != null || got != null) {
			DriftGroup.ocompare(path, want, got).ifPresent(items::add);
		}
	}

	// ─── Request
	// ──────────────────────────────────────────────────────────

	/**
	 * The request that writes {@code args} under {@code name}, with the
	 * conditions the caller built — ref names alone for a repository ruleset
	 * (none at all for a push one), repository conditions too for an
	 * organization one. The rules are whatever the config set; which of them a
	 * target accepts is GitHub's to decide, and a rejected one comes back as a
	 * failed fix.
	 */
	static RulesetRequest request(
			String name,
			Drifty.Ruleset args,
			RulesetRequest.Conditions conditions
	) {
		List<Rule> rules = new ArrayList<>();
		addBooleanRules(args, rules);
		addParameterizedRules(args, rules);
		addPatternRules(args, rules);
		addFileRules(args, rules);

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
		return new RulesetRequest(
				name,
				PklTypes.rulesetTarget(args.target),
				PklTypes.rulesetEnforcement(args.enforcement),
				bypassActors,
				conditions,
				rules
		);
	}

	/**
	 * The ref-name condition, or null for a push or repository target: neither
	 * has refs to condition on, and GitHub rejects the condition there. The
	 * schema keeps the pattern listings empty for those targets.
	 */
	static RulesetRequest.Conditions.RefName refName(Drifty.Ruleset args) {
		if (!hasRefConditions(args)) {
			return null;
		}
		return new RulesetRequest.Conditions.RefName(
				args.includePatterns,
				args.excludePatterns
		);
	}

	static boolean hasRefConditions(Drifty.Ruleset args) {
		return args.target == Drifty.RulesetTarget.BRANCH
				|| args.target == Drifty.RulesetTarget.TAG;
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
									args.strictRequiredStatusChecks
							)
					)
			);
		}
		if (args.pullRequest != null) {
			var pr = args.pullRequest;
			rules.add(
					new Rule.PullRequest(
							new Rule.PullRequest.Parameters(
									(int) pr.requiredApprovingReviewCount,
									pr.dismissStaleReviewsOnPush,
									pr.requireCodeOwnerReview,
									pr.requireLastPushApproval,
									pr.requiredReviewThreadResolution,
									pr.allowedMergeMethods.isEmpty() ? null
											: pr.allowedMergeMethods.stream()
													.map(Object::toString)
													.toList()
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
		if (args.mergeQueue != null) {
			var mq = args.mergeQueue;
			rules.add(
					new Rule.MergeQueue(
							new Rule.MergeQueue.Parameters(
									(int) mq.checkResponseTimeoutMinutes,
									mq.groupingStrategy.toString(),
									(int) mq.maxEntriesToBuild,
									(int) mq.maxEntriesToMerge,
									mq.mergeMethod.toString(),
									(int) mq.minEntriesToMerge,
									(int) mq.minEntriesToMergeWaitMinutes
							)
					)
			);
		}
		if (!args.workflows.isEmpty()) {
			rules.add(
					new Rule.Workflows(
							new Rule.Workflows.Parameters(
									null,
									args.workflows.stream()
											.map(
													w -> new Rule.Workflows.Workflow(
															w.path,
															w.repositoryId,
															w.ref,
															null
													)
											)
											.toList()
							)
					)
			);
		}
	}

	private static void addFileRules(Drifty.Ruleset args, List<Rule> rules) {
		if (!args.filePathRestrictions.isEmpty()) {
			rules.add(
					new Rule.FilePathRestriction(
							new Rule.FilePathRestriction.Parameters(
									args.filePathRestrictions
							)
					)
			);
		}
		if (args.maxFilePathLength != null) {
			rules.add(
					new Rule.MaxFilePathLength(
							new Rule.MaxFilePathLength.Parameters(
									args.maxFilePathLength.intValue()
							)
					)
			);
		}
		if (!args.fileExtensionRestrictions.isEmpty()) {
			rules.add(
					new Rule.FileExtensionRestriction(
							new Rule.FileExtensionRestriction.Parameters(
									args.fileExtensionRestrictions
							)
					)
			);
		}
		if (args.maxFileSize != null) {
			rules.add(
					new Rule.MaxFileSize(
							new Rule.MaxFileSize.Parameters(
									args.maxFileSize.intValue()
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
