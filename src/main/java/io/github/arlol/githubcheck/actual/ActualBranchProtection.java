package io.github.arlol.githubcheck.actual;

import java.util.Optional;
import java.util.Set;

/**
 * Legacy branch protection as it exists on GitHub, flattened to the settings
 * drifty compares.
 * <p>
 * GitHub wraps most of these in {@code {"enabled": bool}} objects, splits
 * status checks across two shapes, and omits whole sections rather than
 * returning them empty. That is the client's business — see
 * {@code ActualTypes}; the comparison sees plain values, with the genuinely
 * optional sections as {@link Optional}.
 */
public record ActualBranchProtection(
		boolean enforceAdmins,
		boolean requiredLinearHistory,
		boolean allowForcePushes,
		boolean allowDeletions,
		boolean blockCreations,
		boolean lockBranch,
		boolean allowForkSyncing,
		boolean requireConversationResolution,
		boolean strictStatusChecks,
		Set<StatusCheck> requiredStatusChecks,
		Optional<PullRequestReviews> pullRequestReviews,
		Optional<Restrictions> restrictions
) {

	/**
	 * The review requirements. The two actor sets name who may dismiss a review
	 * and who may push without one, each as logins, team slugs and app slugs.
	 */
	public record PullRequestReviews(
			boolean dismissStaleReviews,
			boolean requireCodeOwnerReviews,
			Integer requiredApprovingReviewCount,
			Boolean requireLastPushApproval,
			Actors dismissalRestrictions,
			Actors bypassPullRequestAllowances
	) {
	}

	public record Actors(
			Set<String> users,
			Set<String> teams,
			Set<String> apps
	) {

		public static final Actors NONE = new Actors(
				Set.of(),
				Set.of(),
				Set.of()
		);

		public Actors {
			users = Set.copyOf(users);
			teams = Set.copyOf(teams);
			apps = Set.copyOf(apps);
		}

		public boolean isEmpty() {
			return users.isEmpty() && teams.isEmpty() && apps.isEmpty();
		}

	}

	public record Restrictions(
			Set<String> users,
			Set<String> teams,
			Set<String> apps
	) {

		public Restrictions {
			users = Set.copyOf(users);
			teams = Set.copyOf(teams);
			apps = Set.copyOf(apps);
		}

	}

	public ActualBranchProtection {
		requiredStatusChecks = Set.copyOf(requiredStatusChecks);
	}

}
