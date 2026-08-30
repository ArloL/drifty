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
		boolean requireConversationResolution,
		boolean strictStatusChecks,
		Set<StatusCheck> requiredStatusChecks,
		Optional<PullRequestReviews> pullRequestReviews,
		Optional<Restrictions> restrictions
) {

	public record PullRequestReviews(
			boolean dismissStaleReviews,
			boolean requireCodeOwnerReviews,
			Integer requiredApprovingReviewCount,
			Boolean requireLastPushApproval
	) {
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
