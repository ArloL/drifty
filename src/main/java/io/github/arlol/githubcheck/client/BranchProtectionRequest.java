package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record BranchProtectionRequest(
		RequiredStatusChecks requiredStatusChecks,
		boolean enforceAdmins,
		RequiredPullRequestReviews requiredPullRequestReviews,
		Restrictions restrictions,
		boolean requiredLinearHistory,
		boolean allowForcePushes
) {

	public record RequiredStatusChecks(
			boolean strict,
			List<StatusCheck> checks
	) {

		public RequiredStatusChecks {
			checks = List.copyOf(checks);
		}

		public record StatusCheck(
				String context,
				@JsonInclude(JsonInclude.Include.NON_NULL) Integer appId
		) {
		}

	}

	public record RequiredPullRequestReviews(
			boolean dismissStaleReviews,
			boolean requireCodeOwnerReviews,
			Integer requiredApprovingReviewCount,
			Boolean requireLastPushApproval
	) {
	}

	// The write API identifies users/teams/apps by login/slug strings,
	// not by the full objects returned in the response.
	public record Restrictions(
			List<String> users,
			List<String> teams,
			List<String> apps
	) {

		public Restrictions {
			users = List.copyOf(users);
			teams = List.copyOf(teams);
			apps = List.copyOf(apps);
		}

	}

}
