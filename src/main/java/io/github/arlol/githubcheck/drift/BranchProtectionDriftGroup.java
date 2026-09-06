package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.client.BranchProtectionRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

public class BranchProtectionDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, Drifty.BranchProtection> desired;
	private final Map<String, ActualBranchProtection> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public BranchProtectionDriftGroup(
			Map<String, Drifty.BranchProtection> desired,
			Map<String, ActualBranchProtection> actual,
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
	public Drifty.GroupName name() {
		return Drifty.GroupName.BRANCH_PROTECTION;
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
			ActualBranchProtection got = remainingActual.remove(pattern);

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
			ActualBranchProtection got
	) {
		List<DriftItem> items = new ArrayList<>(
				combine(
						compare(
								key(pattern, ".enforce_admins"),
								wanted.enforceAdmins,
								got.enforceAdmins()
						),
						compare(
								key(pattern, ".required_linear_history"),
								wanted.requiredLinearHistory,
								got.requiredLinearHistory()
						),
						compare(
								key(pattern, ".allow_force_pushes"),
								wanted.allowForcePushes,
								got.allowForcePushes()
						),
						compare(
								key(pattern, ".allow_deletions"),
								wanted.allowDeletions,
								got.allowDeletions()
						),
						compare(
								key(pattern, ".block_creations"),
								wanted.blockCreations,
								got.blockCreations()
						),
						compare(
								key(pattern, ".lock_branch"),
								wanted.lockBranch,
								got.lockBranch()
						),
						compare(
								key(pattern, ".allow_fork_syncing"),
								wanted.allowForkSyncing,
								got.allowForkSyncing()
						),
						compare(
								key(
										pattern,
										".require_conversation_resolution"
								),
								wanted.requireConversationResolution,
								got.requireConversationResolution()
						),
						compare(
								key(pattern, ".required_status_checks.strict"),
								wanted.strictStatusChecks,
								got.strictStatusChecks()
						),
						compare(
								key(pattern, ".required_status_checks"),
								desiredStatusChecks(wanted),
								got.requiredStatusChecks()
						)
				)
		);

		comparePullRequestReviews(
				pattern,
				wanted,
				got.pullRequestReviews().orElse(null),
				items
		);
		compareRestrictions(
				pattern,
				wanted,
				got.restrictions().orElse(null),
				items
		);

		return items;
	}

	private static void comparePullRequestReviews(
			String pattern,
			Drifty.BranchProtection wanted,
			ActualBranchProtection.PullRequestReviews rpr,
			List<DriftItem> items
	) {
		String prefix = key(pattern, ".required_pull_request_reviews");
		if (rpr == null) {
			if (wantsPullRequestReviews(wanted)) {
				items.add(new DriftItem.SectionMissing(prefix));
			}
			return;
		}

		ocompare(
				prefix + ".dismiss_stale_reviews",
				wanted.dismissStaleReviews,
				rpr.dismissStaleReviews()
		).ifPresent(items::add);

		ocompare(
				prefix + ".require_code_owner_reviews",
				wanted.requireCodeOwnerReviews,
				rpr.requireCodeOwnerReviews()
		).ifPresent(items::add);

		compareApprovingReviewCount(prefix, wanted, rpr, items);
		compareLastPushApproval(prefix, wanted, rpr, items);
		compareActors(
				prefix + ".dismissal_restrictions",
				wanted.dismissalUsers,
				wanted.dismissalTeams,
				wanted.dismissalApps,
				rpr.dismissalRestrictions(),
				items
		);
		compareActors(
				prefix + ".bypass_pull_request_allowances",
				wanted.bypassPullRequestUsers,
				wanted.bypassPullRequestTeams,
				wanted.bypassPullRequestApps,
				rpr.bypassPullRequestAllowances(),
				items
		);
	}

	private static void compareActors(
			String prefix,
			List<String> users,
			List<String> teams,
			List<String> apps,
			ActualBranchProtection.Actors got,
			List<DriftItem> items
	) {
		items.addAll(
				combine(
						compare(prefix + ".users", users, got.users()),
						compare(prefix + ".teams", teams, got.teams()),
						compare(prefix + ".apps", apps, got.apps())
				)
		);
	}

	private static void compareApprovingReviewCount(
			String prefix,
			Drifty.BranchProtection wanted,
			ActualBranchProtection.PullRequestReviews rpr,
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
							prefix + ".required_approving_review_count",
							wantCount,
							actualCount
					)
			);
		}
	}

	private static void compareLastPushApproval(
			String prefix,
			Drifty.BranchProtection wanted,
			ActualBranchProtection.PullRequestReviews rpr,
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
							prefix + ".require_last_push_approval",
							wantLastPush,
							actualLastPush
					)
			);
		}
	}

	private static void compareRestrictions(
			String pattern,
			Drifty.BranchProtection wanted,
			ActualBranchProtection.Restrictions restrictions,
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

		ocompare(
				key(pattern, ".restrictions.users"),
				wanted.users,
				restrictions.users()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".restrictions.teams"),
				wanted.teams,
				restrictions.teams()
		).ifPresent(items::add);

		ocompare(
				key(pattern, ".restrictions.apps"),
				wanted.apps,
				restrictions.apps()
		).ifPresent(items::add);
	}

	private static boolean wantsPullRequestReviews(
			Drifty.BranchProtection wanted
	) {
		return wanted.dismissStaleReviews || wanted.requireCodeOwnerReviews
				|| wanted.requiredApprovingReviewCount != null
				|| wanted.requireLastPushApproval != null
				|| wantsDismissalRestrictions(wanted)
				|| wantsBypassAllowances(wanted);
	}

	private static boolean wantsDismissalRestrictions(
			Drifty.BranchProtection wanted
	) {
		return !wanted.dismissalUsers.isEmpty()
				|| !wanted.dismissalTeams.isEmpty()
				|| !wanted.dismissalApps.isEmpty();
	}

	private static boolean wantsBypassAllowances(
			Drifty.BranchProtection wanted
	) {
		return !wanted.bypassPullRequestUsers.isEmpty()
				|| !wanted.bypassPullRequestTeams.isEmpty()
				|| !wanted.bypassPullRequestApps.isEmpty();
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
		if (wantsPullRequestReviews(args)) {
			rpr = new BranchProtectionRequest.RequiredPullRequestReviews(
					args.dismissStaleReviews,
					args.requireCodeOwnerReviews,
					args.requiredApprovingReviewCount != null
							? args.requiredApprovingReviewCount.intValue()
							: null,
					args.requireLastPushApproval,
					wantsDismissalRestrictions(args)
							? new BranchProtectionRequest.Actors(
									args.dismissalUsers,
									args.dismissalTeams,
									args.dismissalApps
							)
							: null,
					wantsBypassAllowances(args)
							? new BranchProtectionRequest.Actors(
									args.bypassPullRequestUsers,
									args.bypassPullRequestTeams,
									args.bypassPullRequestApps
							)
							: null
			);
		}

		BranchProtectionRequest.Restrictions restrictions = null;
		if (wantsRestrictions(args)) {
			restrictions = new BranchProtectionRequest.Restrictions(
					args.users,
					args.teams,
					args.apps
			);
		}

		return new BranchProtectionRequest(
				new BranchProtectionRequest.RequiredStatusChecks(
						args.strictStatusChecks,
						checks
				),
				args.enforceAdmins,
				rpr,
				restrictions,
				args.requiredLinearHistory,
				args.allowForcePushes,
				args.allowDeletions,
				args.blockCreations,
				args.requireConversationResolution,
				args.lockBranch,
				args.allowForkSyncing
		);
	}

}
