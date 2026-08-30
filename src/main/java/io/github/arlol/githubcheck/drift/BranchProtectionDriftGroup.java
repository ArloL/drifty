package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.BranchProtectionRequest;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.pkl.Drifty;

public class BranchProtectionDriftGroup extends DriftGroup {

	private final Map<String, Drifty.BranchProtection> desired;
	private final Map<String, BranchProtectionResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public BranchProtectionDriftGroup(
			Map<String, Drifty.BranchProtection> desired,
			Map<String, BranchProtectionResponse> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = Map.copyOf(desired);
		this.actual = Map.copyOf(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public String name() {
		return "branch_protection";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		if (desired.isEmpty() && actual.isEmpty()) {
			return fixes;
		}

		if (actual.isEmpty()) {
			for (var entry : desired.entrySet()) {
				fixes.add(missingFix(entry.getKey(), entry.getValue()));
			}
			return fixes;
		}

		var remainingActual = new HashMap<>(actual);

		for (var entry : desired.entrySet()) {
			String pattern = entry.getKey();
			Drifty.BranchProtection wanted = entry.getValue();
			BranchProtectionResponse got = remainingActual.remove(pattern);

			if (got == null) {
				fixes.add(missingFix(pattern, wanted));
				continue;
			}

			List<DriftItem> items = compareProtection(pattern, wanted, got);
			if (!items.isEmpty()) {
				fixes.add(new DriftFix(items, updateAction(pattern, wanted)));
			}
		}

		for (var actualName : remainingActual.keySet()) {
			var item = new DriftItem.SectionExtra(actualName);
			fixes.add(new DriftFix(item, () -> {
				client.deleteBranchProtection(owner, repo, actualName);
				return FixResult.success();
			}));
		}

		return fixes;
	}

	private DriftFix missingFix(
			String pattern,
			Drifty.BranchProtection wanted
	) {
		return new DriftFix(
				new DriftItem.SectionMissing(pattern),
				updateAction(pattern, wanted)
		);
	}

	private DriftFix.FixAction updateAction(
			String pattern,
			Drifty.BranchProtection wanted
	) {
		return () -> {
			client.updateBranchProtection(
					owner,
					repo,
					pattern,
					buildBranchProtectionRequest(wanted)
			);
			return FixResult.success();
		};
	}

	private List<DriftItem> compareProtection(
			String pattern,
			Drifty.BranchProtection wanted,
			BranchProtectionResponse got
	) {
		List<DriftItem> items = new ArrayList<>();

		ocompare(
				key(pattern, ".enforce_admins"),
				wanted.enforceAdmins,
				got.enforceAdmins().enabled()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".required_linear_history"),
				wanted.requiredLinearHistory,
				got.requiredLinearHistory().enabled()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".allow_force_pushes"),
				wanted.allowForcePushes,
				got.allowForcePushes().enabled()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".require_conversation_resolution"),
				wanted.requireConversationResolution,
				got.requiredConversationResolution() != null
						&& got.requiredConversationResolution().enabled()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".required_status_checks.strict"),
				false,
				got.requiredStatusChecks() != null
						&& got.requiredStatusChecks().strict()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".required_status_checks"),
				desiredStatusChecks(wanted),
				extractActualStatusChecks(got)
		).ifPresent(items::add);

		comparePullRequestReviews(
				pattern,
				wanted,
				got.requiredPullRequestReviews(),
				items
		);
		compareRestrictions(pattern, wanted, got.restrictions(), items);

		return items;
	}

	private static void comparePullRequestReviews(
			String pattern,
			Drifty.BranchProtection wanted,
			BranchProtectionResponse.RequiredPullRequestReviews rpr,
			List<DriftItem> items
	) {
		if (rpr == null) {
			if (wantsPullRequestReviews(wanted)) {
				items.add(
						new DriftItem.SectionMissing(
								key(pattern, ".required_pull_request_reviews")
						)
				);
			}
			return;
		}

		ocompare(
				key(
						pattern,
						".required_pull_request_reviews.dismiss_stale_reviews"
				),
				wanted.dismissStaleReviews,
				rpr.dismissStaleReviews()
		).ifPresent(items::add);

		ocompare(
				key(
						pattern,
						".required_pull_request_reviews.require_code_owner_reviews"
				),
				wanted.requireCodeOwnerReviews,
				rpr.requireCodeOwnerReviews()
		).ifPresent(items::add);

		compareApprovingReviewCount(pattern, wanted, rpr, items);
		compareLastPushApproval(pattern, wanted, rpr, items);
	}

	private static void compareApprovingReviewCount(
			String pattern,
			Drifty.BranchProtection wanted,
			BranchProtectionResponse.RequiredPullRequestReviews rpr,
			List<DriftItem> items
	) {
		Integer wantCount = wanted.requiredApprovingReviewCount != null
				? wanted.requiredApprovingReviewCount.intValue()
				: null;
		Integer actualCount = rpr.requiredApprovingReviewCount();
		boolean drifted = wantCount == null ? actualCount != null
				: !wantCount.equals(actualCount);
		if (drifted) {
			items.add(
					new DriftItem.FieldMismatch(
							key(
									pattern,
									".required_pull_request_reviews.required_approving_review_count"
							),
							wantCount,
							actualCount
					)
			);
		}
	}

	private static void compareLastPushApproval(
			String pattern,
			Drifty.BranchProtection wanted,
			BranchProtectionResponse.RequiredPullRequestReviews rpr,
			List<DriftItem> items
	) {
		Boolean wantLastPush = wanted.requireLastPushApproval;
		Boolean actualLastPush = rpr.requireLastPushApproval();
		boolean drifted = wantLastPush == null
				? Boolean.TRUE.equals(actualLastPush)
				: !wantLastPush.equals(actualLastPush);
		if (drifted) {
			items.add(
					new DriftItem.FieldMismatch(
							key(
									pattern,
									".required_pull_request_reviews.require_last_push_approval"
							),
							wantLastPush,
							actualLastPush
					)
			);
		}
	}

	private static void compareRestrictions(
			String pattern,
			Drifty.BranchProtection wanted,
			BranchProtectionResponse.Restrictions restrictions,
			List<DriftItem> items
	) {
		if (restrictions == null) {
			if (wantsRestrictions(wanted)) {
				items.add(
						new DriftItem.SectionMissing(
								key(pattern, ".restrictions")
						)
				);
			}
			return;
		}

		Set<String> actualUsers = restrictions.users()
				.stream()
				.map(SimpleUser::login)
				.collect(Collectors.toSet());
		ocompare(key(pattern, ".restrictions.users"), wanted.users, actualUsers)
				.ifPresent(items::add);

		Set<String> actualTeams = restrictions.teams()
				.stream()
				.map(BranchProtectionResponse.Restrictions.Team::slug)
				.collect(Collectors.toSet());
		ocompare(key(pattern, ".restrictions.teams"), wanted.teams, actualTeams)
				.ifPresent(items::add);

		Set<String> actualApps = restrictions.apps()
				.stream()
				.map(BranchProtectionResponse.Restrictions.App::slug)
				.collect(Collectors.toSet());
		ocompare(key(pattern, ".restrictions.apps"), wanted.apps, actualApps)
				.ifPresent(items::add);
	}

	private static boolean wantsPullRequestReviews(
			Drifty.BranchProtection wanted
	) {
		return wanted.dismissStaleReviews || wanted.requireCodeOwnerReviews
				|| wanted.requiredApprovingReviewCount != null
				|| wanted.requireLastPushApproval != null;
	}

	private static boolean wantsRestrictions(Drifty.BranchProtection wanted) {
		return !wanted.users.isEmpty() || !wanted.teams.isEmpty()
				|| !wanted.apps.isEmpty();
	}

	private static String key(String pattern, String suffix) {
		return pattern + suffix;
	}

	private static Set<StatusCheck> desiredStatusChecks(
			Drifty.BranchProtection bp
	) {
		Set<StatusCheck> checks = new HashSet<>();
		for (var sc : bp.requiredStatusChecks) {
			checks.add(
					new StatusCheck(
							sc.context,
							sc.appId != null ? sc.appId.intValue() : null
					)
			);
		}
		return checks;
	}

	private Set<StatusCheck> extractActualStatusChecks(
			BranchProtectionResponse bp
	) {
		var rsc = bp.requiredStatusChecks();
		if (rsc == null) {
			return Set.of();
		}

		Set<StatusCheck> checks = new HashSet<>();

		if (rsc.checks() != null && !rsc.checks().isEmpty()) {
			for (var c : rsc.checks()) {
				checks.add(new StatusCheck(c.context(), c.appId()));
			}
		} else if (rsc.contexts() != null) {
			for (var c : rsc.contexts()) {
				checks.add(new StatusCheck(c, null));
			}
		}

		return checks;
	}

	private static BranchProtectionRequest buildBranchProtectionRequest(
			Drifty.BranchProtection args
	) {
		var checks = args.requiredStatusChecks.stream()
				.map(
						sc -> new BranchProtectionRequest.RequiredStatusChecks.StatusCheck(
								sc.context,
								sc.appId != null ? sc.appId.intValue() : null
						)
				)
				.toList();

		BranchProtectionRequest.RequiredPullRequestReviews rpr = null;
		boolean hasPrReviews = args.dismissStaleReviews
				|| args.requireCodeOwnerReviews
				|| args.requiredApprovingReviewCount != null
				|| args.requireLastPushApproval != null;
		if (hasPrReviews) {
			rpr = new BranchProtectionRequest.RequiredPullRequestReviews(
					args.dismissStaleReviews,
					args.requireCodeOwnerReviews,
					args.requiredApprovingReviewCount != null
							? args.requiredApprovingReviewCount.intValue()
							: null,
					args.requireLastPushApproval
			);
		}

		BranchProtectionRequest.Restrictions restrictions = null;
		if (!args.users.isEmpty() || !args.teams.isEmpty()
				|| !args.apps.isEmpty()) {
			restrictions = new BranchProtectionRequest.Restrictions(
					args.users,
					args.teams,
					args.apps
			);
		}

		return new BranchProtectionRequest(
				new BranchProtectionRequest.RequiredStatusChecks(false, checks),
				args.enforceAdmins,
				rpr,
				restrictions,
				args.requiredLinearHistory,
				args.allowForcePushes
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
