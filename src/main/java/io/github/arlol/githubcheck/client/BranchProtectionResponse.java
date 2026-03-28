package io.github.arlol.githubcheck.client;

import java.util.List;

public record BranchProtectionResponse(
		String url, // optional
		Boolean enabled, // optional
		EnforceAdmins enforceAdmins,
		RequiredLinearHistory requiredLinearHistory,
		AllowForcePushes allowForcePushes,
		AllowDeletions allowDeletions, // optional
		BlockCreations blockCreations, // optional
		RequiredConversationResolution requiredConversationResolution, // optional
		// Absent when no status-check rules are configured.
		RequiredStatusChecks requiredStatusChecks,
		RequiredPullRequestReviews requiredPullRequestReviews, // optional
		Restrictions restrictions, // optional
		String name, // optional
		String protectionUrl, // optional
		RequiredSignatures requiredSignatures, // optional
		LockBranch lockBranch, // optional
		AllowForkSyncing allowForkSyncing // optional
) {

	public record EnforceAdmins(
			String url,
			boolean enabled
	) {
	}

	public record RequiredLinearHistory(
			boolean enabled
	) {
	}

	public record AllowForcePushes(
			boolean enabled
	) {
	}

	public record AllowDeletions(
			boolean enabled
	) {
	}

	public record BlockCreations(
			boolean enabled
	) {
	}

	public record RequiredConversationResolution(
			boolean enabled
	) {
	}

	public record RequiredSignatures(
			String url,
			boolean enabled
	) {
	}

	public record LockBranch(
			Boolean enabled // optional, default false
	) {
	}

	public record AllowForkSyncing(
			Boolean enabled // optional, default false
	) {
	}

	public record RequiredStatusChecks(
			String url, // optional
			String enforcementLevel, // optional
			boolean strict,
			// Modern API returns checks[].context; legacy returns contexts[].
			List<StatusCheck> checks,
			List<String> contexts,
			String contextsUrl // optional
	) {

		public RequiredStatusChecks {
			checks = checks == null ? null : List.copyOf(checks);
			contexts = contexts == null ? null : List.copyOf(contexts);
		}

		public record StatusCheck(
				String context,
				Integer appId // nullable
		) {
		}

	}

	public record RequiredPullRequestReviews(
			String url, // optional
			boolean dismissStaleReviews,
			boolean requireCodeOwnerReviews,
			Integer requiredApprovingReviewCount, // optional
			Boolean requireLastPushApproval // optional, default false
	) {
	}

	public record Restrictions(
			String url,
			String usersUrl,
			String teamsUrl,
			String appsUrl,
			List<SimpleUser> users,
			List<Team> teams,
			List<App> apps
	) {

		public Restrictions {
			users = users == null ? null : List.copyOf(users);
			teams = teams == null ? null : List.copyOf(teams);
			apps = apps == null ? null : List.copyOf(apps);
		}

		public record Team(
				Long id,
				String nodeId,
				String name,
				String slug,
				String permission
		) {
		}

		public record App(
				Long id,
				String nodeId,
				String slug,
				String name,
				String clientId,
				String description,
				String externalUrl,
				String htmlUrl,
				String createdAt,
				String updatedAt
		) {
		}

	}

}
