package io.github.arlol.githubcheck.config;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BranchProtectionArgs {

	private final String pattern;
	private final boolean enforceAdmins;
	private final boolean requiredLinearHistory;
	private final boolean allowForcePushes;
	private final boolean requireConversationResolution;
	private final Set<StatusCheckArgs> requiredStatusChecks;
	private final Integer requiredApprovingReviewCount;
	private final boolean dismissStaleReviews;
	private final boolean requireCodeOwnerReviews;
	private final Boolean requireLastPushApproval;
	private final List<String> users;
	private final List<String> teams;
	private final List<String> apps;

	private BranchProtectionArgs(Builder builder) {
		this.pattern = builder.pattern;
		this.enforceAdmins = builder.enforceAdmins;
		this.requiredLinearHistory = builder.requiredLinearHistory;
		this.allowForcePushes = builder.allowForcePushes;
		this.requireConversationResolution = builder.requireConversationResolution;
		this.requiredStatusChecks = Set.copyOf(builder.requiredStatusChecks);
		this.requiredApprovingReviewCount = builder.requiredApprovingReviewCount;
		this.dismissStaleReviews = builder.dismissStaleReviews;
		this.requireCodeOwnerReviews = builder.requireCodeOwnerReviews;
		this.requireLastPushApproval = builder.requireLastPushApproval;
		this.users = List.copyOf(builder.users);
		this.teams = List.copyOf(builder.teams);
		this.apps = List.copyOf(builder.apps);
	}

	public Integer requiredApprovingReviewCount() {
		return requiredApprovingReviewCount;
	}

	public boolean dismissStaleReviews() {
		return dismissStaleReviews;
	}

	public boolean requireCodeOwnerReviews() {
		return requireCodeOwnerReviews;
	}

	public Boolean requireLastPushApproval() {
		return requireLastPushApproval;
	}

	public List<String> users() {
		return users;
	}

	public List<String> teams() {
		return teams;
	}

	public List<String> apps() {
		return apps;
	}

	public String pattern() {
		return pattern;
	}

	public boolean enforceAdmins() {
		return enforceAdmins;
	}

	public boolean requiredLinearHistory() {
		return requiredLinearHistory;
	}

	public boolean allowForcePushes() {
		return allowForcePushes;
	}

	public boolean requireConversationResolution() {
		return requireConversationResolution;
	}

	public Set<StatusCheckArgs> requiredStatusChecks() {
		return requiredStatusChecks;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		BranchProtectionArgs that = (BranchProtectionArgs) o;
		return enforceAdmins == that.enforceAdmins
				&& requiredLinearHistory == that.requiredLinearHistory
				&& allowForcePushes == that.allowForcePushes
				&& requireConversationResolution == that.requireConversationResolution
				&& dismissStaleReviews == that.dismissStaleReviews
				&& requireCodeOwnerReviews == that.requireCodeOwnerReviews
				&& Objects.equals(pattern, that.pattern)
				&& Objects.equals(
						requiredApprovingReviewCount,
						that.requiredApprovingReviewCount
				)
				&& Objects.equals(
						requireLastPushApproval,
						that.requireLastPushApproval
				) && Objects.equals(users, that.users)
				&& Objects.equals(teams, that.teams)
				&& Objects.equals(apps, that.apps)
				&& Objects.equals(
						requiredStatusChecks,
						that.requiredStatusChecks
				);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				pattern,
				enforceAdmins,
				requiredLinearHistory,
				allowForcePushes,
				requireConversationResolution,
				requiredStatusChecks,
				requiredApprovingReviewCount,
				dismissStaleReviews,
				requireCodeOwnerReviews,
				requireLastPushApproval,
				users,
				teams,
				apps
		);
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder(String pattern) {
		return new Builder(pattern);
	}

	public static final class Builder {

		private String pattern;
		private boolean enforceAdmins = false;
		private boolean requiredLinearHistory = false;
		private boolean allowForcePushes = false;
		private boolean requireConversationResolution = false;
		private Set<StatusCheckArgs> requiredStatusChecks = Set.of();
		private Integer requiredApprovingReviewCount = null;
		private boolean dismissStaleReviews = false;
		private boolean requireCodeOwnerReviews = false;
		private Boolean requireLastPushApproval = null;
		private List<String> users = List.of();
		private List<String> teams = List.of();
		private List<String> apps = List.of();

		public Builder(String pattern) {
			this.pattern = pattern;
		}

		public Builder(BranchProtectionArgs args) {
			this.pattern = args.pattern;
			this.enforceAdmins = args.enforceAdmins;
			this.requiredLinearHistory = args.requiredLinearHistory;
			this.allowForcePushes = args.allowForcePushes;
			this.requireConversationResolution = args.requireConversationResolution;
			this.requiredStatusChecks = args.requiredStatusChecks;
			this.requiredApprovingReviewCount = args.requiredApprovingReviewCount;
			this.dismissStaleReviews = args.dismissStaleReviews;
			this.requireCodeOwnerReviews = args.requireCodeOwnerReviews;
			this.requireLastPushApproval = args.requireLastPushApproval;
			this.users = args.users;
			this.teams = args.teams;
			this.apps = args.apps;
		}

		public Builder pattern(String pattern) {
			this.pattern = pattern;
			return this;
		}

		public Builder enforceAdmins(boolean enforceAdmins) {
			this.enforceAdmins = enforceAdmins;
			return this;
		}

		public Builder requiredLinearHistory(boolean requiredLinearHistory) {
			this.requiredLinearHistory = requiredLinearHistory;
			return this;
		}

		public Builder allowForcePushes(boolean allowForcePushes) {
			this.allowForcePushes = allowForcePushes;
			return this;
		}

		public Builder requireConversationResolution(
				boolean requireConversationResolution
		) {
			this.requireConversationResolution = requireConversationResolution;
			return this;
		}

		public Builder requiredStatusChecks(StatusCheckArgs... checks) {
			this.requiredStatusChecks = Set.of(checks);
			return this;
		}

		public Builder requiredStatusChecks(Set<StatusCheckArgs> checks) {
			this.requiredStatusChecks = Set.copyOf(checks);
			return this;
		}

		public Builder addRequiredStatusChecks(StatusCheckArgs... checks) {
			Set<StatusCheckArgs> combined = new HashSet<>(
					this.requiredStatusChecks
			);
			combined.addAll(Set.of(checks));
			this.requiredStatusChecks = combined;
			return this;
		}

		public Builder requiredApprovingReviewCount(Integer count) {
			this.requiredApprovingReviewCount = count;
			return this;
		}

		public Builder dismissStaleReviews(boolean dismissStaleReviews) {
			this.dismissStaleReviews = dismissStaleReviews;
			return this;
		}

		public Builder requireCodeOwnerReviews(
				boolean requireCodeOwnerReviews
		) {
			this.requireCodeOwnerReviews = requireCodeOwnerReviews;
			return this;
		}

		public Builder requireLastPushApproval(
				Boolean requireLastPushApproval
		) {
			this.requireLastPushApproval = requireLastPushApproval;
			return this;
		}

		public Builder users(List<String> users) {
			this.users = List.copyOf(users);
			return this;
		}

		public Builder teams(List<String> teams) {
			this.teams = List.copyOf(teams);
			return this;
		}

		public Builder apps(List<String> apps) {
			this.apps = List.copyOf(apps);
			return this;
		}

		public BranchProtectionArgs build() {
			return new BranchProtectionArgs(this);
		}

	}

}
