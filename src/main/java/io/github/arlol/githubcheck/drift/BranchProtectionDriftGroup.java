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
import io.github.arlol.githubcheck.config.BranchProtectionArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

public class BranchProtectionDriftGroup extends DriftGroup {

	private final Map<String, BranchProtectionArgs> desired;
	private final Map<String, BranchProtectionResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public BranchProtectionDriftGroup(
			RepositoryArgs desired,
			Map<String, BranchProtectionResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Map.copyOf(desired.branchProtections());
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
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();

		if (desired.isEmpty() && actual.isEmpty()) {
			return items;
		}

		if (actual.isEmpty()) {
			for (var wanted : desired.values()) {
				items.add(
						new DriftItem.SectionMissing(
								"branch_protection." + wanted.pattern()
						)
				);
			}
			return items;
		}

		var remainingActual = new HashMap<>(actual);

		for (var entry : desired.entrySet()) {
			String pattern = entry.getKey();
			BranchProtectionArgs wanted = entry.getValue();
			BranchProtectionResponse got = remainingActual.remove(pattern);

			if (got == null) {
				items.add(
						new DriftItem.SectionMissing(
								"branch_protection." + pattern
						)
				);
				continue;
			}

			items.addAll(
					compare(
							"branch_protection." + pattern + ".enforce_admins",
							wanted.enforceAdmins(),
							got.enforceAdmins().enabled()
					)
			);

			items.addAll(
					compare(
							"branch_protection." + pattern
									+ ".required_linear_history",
							wanted.requiredLinearHistory(),
							got.requiredLinearHistory().enabled()
					)
			);

			items.addAll(
					compare(
							"branch_protection." + pattern
									+ ".allow_force_pushes",
							wanted.allowForcePushes(),
							got.allowForcePushes().enabled()
					)
			);

			items.addAll(
					compare(
							"branch_protection." + pattern
									+ ".require_conversation_resolution",
							wanted.requireConversationResolution(),
							got.requiredConversationResolution() != null
									&& got.requiredConversationResolution()
											.enabled()
					)
			);

			var rsc = got.requiredStatusChecks();
			boolean strict = rsc != null && rsc.strict();
			items.addAll(
					compare(
							"branch_protection." + pattern
									+ ".required_status_checks.strict",
							false,
							strict
					)
			);

			Set<StatusCheckArgs> wantedChecks = wanted.requiredStatusChecks();
			Set<StatusCheckArgs> actualChecks = extractActualStatusChecks(got);
			items.addAll(
					compareSets(
							"branch_protection." + pattern
									+ ".required_status_checks",
							wantedChecks,
							actualChecks
					)
			);

			var rpr = got.requiredPullRequestReviews();
			if (rpr == null) {
				if (wanted.dismissStaleReviews()
						|| wanted.requireCodeOwnerReviews()
						|| wanted.requiredApprovingReviewCount() != null
						|| wanted.requireLastPushApproval() != null) {
					items.add(
							new DriftItem.SectionMissing(
									"branch_protection." + pattern
											+ ".required_pull_request_reviews"
							)
					);
				}
			} else {
				items.addAll(
						compare(
								"branch_protection." + pattern
										+ ".required_pull_request_reviews.dismiss_stale_reviews",
								wanted.dismissStaleReviews(),
								rpr.dismissStaleReviews()
						)
				);

				items.addAll(
						compare(
								"branch_protection." + pattern
										+ ".required_pull_request_reviews.require_code_owner_reviews",
								wanted.requireCodeOwnerReviews(),
								rpr.requireCodeOwnerReviews()
						)
				);

				Integer wantCount = wanted.requiredApprovingReviewCount();
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

				Boolean wantLastPush = wanted.requireLastPushApproval();
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
				if (!wanted.users().isEmpty() || !wanted.teams().isEmpty()
						|| !wanted.apps().isEmpty()) {
					items.add(
							new DriftItem.SectionMissing(
									"branch_protection." + pattern
											+ ".restrictions"
							)
					);
				}
			} else {
				Set<String> wantUsers = new HashSet<>(wanted.users());
				Set<String> actualUsers = restrictions.users()
						.stream()
						.map(SimpleUser::login)
						.collect(Collectors.toSet());
				items.addAll(
						compareSets(
								"branch_protection." + pattern
										+ ".restrictions.users",
								wantUsers,
								actualUsers
						)
				);

				Set<String> wantTeams = new HashSet<>(wanted.teams());
				Set<String> actualTeams = restrictions.teams()
						.stream()
						.map(BranchProtectionResponse.Restrictions.Team::slug)
						.collect(Collectors.toSet());
				items.addAll(
						compareSets(
								"branch_protection." + pattern
										+ ".restrictions.teams",
								wantTeams,
								actualTeams
						)
				);

				Set<String> wantApps = new HashSet<>(wanted.apps());
				Set<String> actualApps = restrictions.apps()
						.stream()
						.map(BranchProtectionResponse.Restrictions.App::slug)
						.collect(Collectors.toSet());
				items.addAll(
						compareSets(
								"branch_protection." + pattern
										+ ".restrictions.apps",
								wantApps,
								actualApps
						)
				);
			}
		}

		for (var actualName : remainingActual.keySet()) {
			items.add(
					new DriftItem.SectionExtra(
							"branch_protection." + actualName
					)
			);
		}

		return items;
	}

	private Set<StatusCheckArgs> extractActualStatusChecks(
			BranchProtectionResponse bp
	) {
		var rsc = bp.requiredStatusChecks();
		if (rsc == null) {
			return Set.of();
		}

		Set<StatusCheckArgs> checks = new HashSet<>();

		if (rsc.checks() != null && !rsc.checks().isEmpty()) {
			for (var c : rsc.checks()) {
				checks.add(
						StatusCheckArgs.builder()
								.context(c.context())
								.appId(c.appId())
								.build()
				);
			}
		} else if (rsc.contexts() != null) {
			for (var c : rsc.contexts()) {
				checks.add(StatusCheckArgs.builder().context(c).build());
			}
		}

		return checks;
	}

	@Override
	public FixResult fix() {
		for (var entry : desired.entrySet()) {
			String pattern = entry.getKey();
			BranchProtectionArgs wanted = entry.getValue();

			var payload = buildBranchProtectionRequest(wanted);
			client.updateBranchProtection(owner, repo, pattern, payload);
		}
		return FixResult.success();
	}

	private static BranchProtectionRequest buildBranchProtectionRequest(
			BranchProtectionArgs args
	) {
		var checks = args.requiredStatusChecks()
				.stream()
				.map(
						sc -> new BranchProtectionRequest.RequiredStatusChecks.StatusCheck(
								sc.getContext(),
								sc.getAppId()
						)
				)
				.toList();

		BranchProtectionRequest.RequiredPullRequestReviews rpr = null;
		boolean hasPrReviews = args.dismissStaleReviews()
				|| args.requireCodeOwnerReviews()
				|| args.requiredApprovingReviewCount() != null
				|| args.requireLastPushApproval() != null;
		if (hasPrReviews) {
			rpr = new BranchProtectionRequest.RequiredPullRequestReviews(
					args.dismissStaleReviews(),
					args.requireCodeOwnerReviews(),
					args.requiredApprovingReviewCount(),
					args.requireLastPushApproval()
			);
		}

		BranchProtectionRequest.Restrictions restrictions = null;
		if (!args.users().isEmpty() || !args.teams().isEmpty()
				|| !args.apps().isEmpty()) {
			restrictions = new BranchProtectionRequest.Restrictions(
					args.users(),
					args.teams(),
					args.apps()
			);
		}

		return new BranchProtectionRequest(
				new BranchProtectionRequest.RequiredStatusChecks(false, checks),
				args.enforceAdmins(),
				rpr,
				restrictions,
				args.requiredLinearHistory(),
				args.allowForcePushes()
		);
	}

}
