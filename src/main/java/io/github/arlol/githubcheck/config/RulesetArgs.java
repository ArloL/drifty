package io.github.arlol.githubcheck.config;

import java.util.ArrayList;
import java.util.List;

public final class RulesetArgs {

	private final String name;
	private final List<String> includePatterns;
	private final boolean requiredLinearHistory;
	private final boolean noForcePushes;
	private final List<String> requiredStatusChecks;
	private final Integer requiredReviewCount;

	private RulesetArgs(Builder builder) {
		this.name = builder.name;
		this.includePatterns = List.copyOf(builder.includePatterns);
		this.requiredLinearHistory = builder.requiredLinearHistory;
		this.noForcePushes = builder.noForcePushes;
		this.requiredStatusChecks = List.copyOf(builder.requiredStatusChecks);
		this.requiredReviewCount = builder.requiredReviewCount;
	}

	public String name() {
		return name;
	}

	public List<String> includePatterns() {
		return includePatterns;
	}

	public boolean requiredLinearHistory() {
		return requiredLinearHistory;
	}

	public boolean noForcePushes() {
		return noForcePushes;
	}

	public List<String> requiredStatusChecks() {
		return requiredStatusChecks;
	}

	public Integer requiredReviewCount() {
		return requiredReviewCount;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static final class Builder {

		private String name;
		private List<String> includePatterns = List.of();
		private boolean requiredLinearHistory = false;
		private boolean noForcePushes = false;
		private List<String> requiredStatusChecks = List.of();
		private Integer requiredReviewCount = null;

		public Builder(String name) {
			this.name = name;
		}

		public Builder(RulesetArgs rulesetArgs) {
			this.name = rulesetArgs.name;
			this.includePatterns = rulesetArgs.includePatterns;
			this.requiredLinearHistory = rulesetArgs.requiredLinearHistory;
			this.noForcePushes = rulesetArgs.noForcePushes;
			this.requiredStatusChecks = rulesetArgs.requiredStatusChecks;
			this.requiredReviewCount = rulesetArgs.requiredReviewCount;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder includePatterns(String... patterns) {
			this.includePatterns = List.of(patterns);
			return this;
		}

		public Builder requiredLinearHistory(boolean requiredLinearHistory) {
			this.requiredLinearHistory = requiredLinearHistory;
			return this;
		}

		public Builder noForcePushes(boolean noForcePushes) {
			this.noForcePushes = noForcePushes;
			return this;
		}

		public Builder requiredStatusChecks(String... checks) {
			this.requiredStatusChecks = List.of(checks);
			return this;
		}

		public Builder addRequiredStatusChecks(String... checks) {
			List<String> combined = new ArrayList<>(this.requiredStatusChecks);
			combined.addAll(List.of(checks));
			this.requiredStatusChecks = combined;
			return this;
		}

		public Builder requiredReviewCount(Integer requiredReviewCount) {
			this.requiredReviewCount = requiredReviewCount;
			return this;
		}

		public RulesetArgs build() {
			return new RulesetArgs(this);
		}

	}

}
