package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/branches/{branch}/protection",
		undocumented = {
				"restrictions.users.name — SimpleUser is one record for every user GitHub returns; the narrower user object here omits it",
				"restrictions.users.email — as above" },
		unmanaged = {
				"restrictions.apps.* — BranchProtectionDriftGroup compares an actor by the field that names it, an app by slug; the rest describes the app rather than the protection",
				"restrictions.teams.* — compared by slug, as above",
				"required_pull_request_reviews.dismissal_restrictions.apps.* — compared by slug, as above",
				"required_pull_request_reviews.dismissal_restrictions.teams.* — compared by slug, as above",
				"required_pull_request_reviews.dismissal_restrictions.url — navigation GitHub supplies for the restriction object",
				"required_pull_request_reviews.bypass_pull_request_allowances.apps.* — compared by slug, as above",
				"required_pull_request_reviews.bypass_pull_request_allowances.teams.* — compared by slug, as above" }
)
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
			@Nullable String url, // optional
			EnforcementLevel enforcementLevel, // optional
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

		public enum EnforcementLevel {
			@JsonProperty("off")
			OFF, @JsonProperty("non_admins")
			NON_ADMINS, @JsonProperty("everyone")
			EVERYONE
		}

	}

	public record RequiredPullRequestReviews(
			String url, // optional
			boolean dismissStaleReviews,
			boolean requireCodeOwnerReviews,
			@Nullable Integer requiredApprovingReviewCount, // optional
			@Nullable Boolean requireLastPushApproval, // optional, default
													   // false
			@Nullable Actors dismissalRestrictions, // optional
			@Nullable Actors bypassPullRequestAllowances // optional
	) {

		public RequiredPullRequestReviews(
				String url,
				boolean dismissStaleReviews,
				boolean requireCodeOwnerReviews,
				Integer requiredApprovingReviewCount,
				Boolean requireLastPushApproval
		) {
			this(
					url,
					dismissStaleReviews,
					requireCodeOwnerReviews,
					requiredApprovingReviewCount,
					requireLastPushApproval,
					null,
					null
			);
		}

	}

	/**
	 * The users, teams and apps a review sub-setting names — the shape of both
	 * {@code dismissal_restrictions} and
	 * {@code bypass_pull_request_allowances}.
	 */
	public record Actors(
			List<SimpleUser> users,
			List<Restrictions.Team> teams,
			List<Restrictions.App> apps
	) {

		public Actors {
			users = users == null ? List.of() : List.copyOf(users);
			teams = teams == null ? List.of() : List.copyOf(teams);
			apps = apps == null ? List.of() : List.copyOf(apps);
		}

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
