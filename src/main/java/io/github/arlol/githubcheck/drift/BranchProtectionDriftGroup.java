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
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.pkl.Drifty;

public class BranchProtectionDriftGroup extends DriftGroup {

	private final Map<String, Drifty.BranchProtection> desired;
	private final Map<String, BranchProtectionResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public BranchProtectionDriftGroup(
			Drifty.Repository desired,
			Map<String, BranchProtectionResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Map.copyOf(desired.branchProtections);
		this.actual = Map.copyOf(actual);
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "branch_protection";
	}

	@Override
	public List<DriftFix> detect() {
		var fixes = new ArrayList<DriftFix>();

		if (desired.isEmpty() && actual.isEmpty()) {
			return fixes;
		}

		if (actual.isEmpty()) {
			for (var entry : desired.entrySet()) {
				String pattern = entry.getKey();
				var wanted = entry.getValue();
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(
										"branch_protection." + pattern
								),
								() -> {
									client.updateBranchProtection(
											owner,
											repo,
											pattern,
											buildBranchProtectionRequest(wanted)
									);
									return FixResult.success();
								}
						)
				);
			}
			return fixes;
		}

		var remainingActual = new HashMap<>(actual);

		for (var entry : desired.entrySet()) {
			String pattern = entry.getKey();
			Drifty.BranchProtection wanted = entry.getValue();
			BranchProtectionResponse got = remainingActual.remove(pattern);

			if (got == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(
										"branch_protection." + pattern
								),
								() -> {
									client.updateBranchProtection(
											owner,
											repo,
											pattern,
											buildBranchProtectionRequest(wanted)
									);
									return FixResult.success();
								}
						)
				);
				continue;
			}

			List<DriftItem> items = new ArrayList<>();

			ocompare(
					"branch_protection." + pattern + ".enforce_admins",
					wanted.enforceAdmins,
					got.enforceAdmins().enabled()
			).ifPresent(items::add);

			ocompare(
					"branch_protection." + pattern + ".required_linear_history",
					wanted.requiredLinearHistory,
					got.requiredLinearHistory().enabled()
			).ifPresent(items::add);

			ocompare(
					"branch_protection." + pattern + ".allow_force_pushes",
					wanted.allowForcePushes,
					got.allowForcePushes().enabled()
			).ifPresent(items::add);

			ocompare(
					"branch_protection." + pattern
							+ ".require_conversation_resolution",
					wanted.requireConversationResolution,
					got.requiredConversationResolution() != null
							&& got.requiredConversationResolution().enabled()
			).ifPresent(items::add);

			ocompare(
					"branch_protection." + pattern
							+ ".required_status_checks.strict",
					false,
					got.requiredStatusChecks() != null
							&& got.requiredStatusChecks().strict()
			).ifPresent(items::add);

			ocompare(
					"branch_protection." + pattern + ".required_status_checks",
					desiredStatusChecks(wanted),
					extractActualStatusChecks(got)
			).ifPresent(items::add);

			var rpr = got.requiredPullRequestReviews();
			if (rpr == null) {
				if (wanted.dismissStaleReviews || wanted.requireCodeOwnerReviews
						|| wanted.requiredApprovingReviewCount != null
						|| wanted.requireLastPushApproval != null) {
					items.add(
							new DriftItem.SectionMissing(
									"branch_protection." + pattern
											+ ".required_pull_request_reviews"
							)
					);
				}
			} else {
				ocompare(
						"branch_protection." + pattern
								+ ".required_pull_request_reviews.dismiss_stale_reviews",
						wanted.dismissStaleReviews,
						rpr.dismissStaleReviews()
				).ifPresent(items::add);

				ocompare(
						"branch_protection." + pattern
								+ ".required_pull_request_reviews.require_code_owner_reviews",
						wanted.requireCodeOwnerReviews,
						rpr.requireCodeOwnerReviews()
				).ifPresent(items::add);

				Integer wantCount = wanted.requiredApprovingReviewCount != null
						? wanted.requiredApprovingReviewCount.intValue()
						: null;
				Integer actualCount = rpr.requiredApprovingReviewCount();
				if ((wantCount == null && actualCount != null)
						|| (wantCount != null
								&& !wantCount.equals(actualCount))) {
					items.add(
							new DriftItem.FieldMismatch(
									"branch_protection." + pattern
											+ ".required_pull_request_reviews.required_approving_review_count",
									wantCount,
									actualCount
							)
					);
				}

				Boolean wantLastPush = wanted.requireLastPushApproval;
				Boolean actualLastPush = rpr.requireLastPushApproval();
				if ((wantLastPush == null && actualLastPush != null
						&& actualLastPush)
						|| (wantLastPush != null
								&& !wantLastPush.equals(actualLastPush))) {
					items.add(
							new DriftItem.FieldMismatch(
									"branch_protection." + pattern
											+ ".required_pull_request_reviews.require_last_push_approval",
									wantLastPush,
									actualLastPush
							)
					);
				}
			}

			var restrictions = got.restrictions();
			if (restrictions == null) {
				if (!wanted.users.isEmpty() || !wanted.teams.isEmpty()
						|| !wanted.apps.isEmpty()) {
					items.add(
							new DriftItem.SectionMissing(
									"branch_protection." + pattern
											+ ".restrictions"
							)
					);
				}
			} else {
				Set<String> actualUsers = restrictions.users()
						.stream()
						.map(SimpleUser::login)
						.collect(Collectors.toSet());
				ocompare(
						"branch_protection." + pattern + ".restrictions.users",
						wanted.users,
						actualUsers
				).ifPresent(items::add);

				Set<String> actualTeams = restrictions.teams()
						.stream()
						.map(BranchProtectionResponse.Restrictions.Team::slug)
						.collect(Collectors.toSet());
				ocompare(
						"branch_protection." + pattern + ".restrictions.teams",
						wanted.teams,
						actualTeams
				).ifPresent(items::add);

				Set<String> actualApps = restrictions.apps()
						.stream()
						.map(BranchProtectionResponse.Restrictions.App::slug)
						.collect(Collectors.toSet());
				ocompare(
						"branch_protection." + pattern + ".restrictions.apps",
						wanted.apps,
						actualApps
				).ifPresent(items::add);
			}

			if (!items.isEmpty()) {
				fixes.add(new DriftFix(items, () -> {
					client.updateBranchProtection(
							owner,
							repo,
							pattern,
							buildBranchProtectionRequest(wanted)
					);
					return FixResult.success();
				}));
			}
		}

		for (var actualName : remainingActual.keySet()) {
			var item = new DriftItem.SectionExtra(
					"branch_protection." + actualName
			);
			fixes.add(new DriftFix(item, () -> {
				client.deleteBranchProtection(owner, repo, actualName);
				return FixResult.success();
			}));
		}

		return fixes;
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
