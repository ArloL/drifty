package io.github.arlol.githubcheck.config;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.arlol.githubcheck.config.CodeScanningToolArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

public final class RulesetArgs {

	private final String name;
	private final List<String> includePatterns;
	private final boolean requiredLinearHistory;
	private final boolean noForcePushes;
	private final Set<StatusCheckArgs> requiredStatusChecks;
	private final Integer requiredReviewCount;
	private final Set<CodeScanningToolArgs> requiredCodeScanning;

	private RulesetArgs(Builder builder) {
		this.name = builder.name;
		this.includePatterns = List.copyOf(builder.includePatterns);
		this.requiredLinearHistory = builder.requiredLinearHistory;
		this.noForcePushes = builder.noForcePushes;
		this.requiredStatusChecks = Set.copyOf(builder.requiredStatusChecks);
		this.requiredReviewCount = builder.requiredReviewCount;
		this.requiredCodeScanning = Set.copyOf(builder.requiredCodeScanning);
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

	public Set<StatusCheckArgs> requiredStatusChecks() {
		return requiredStatusChecks;
	}

	public Integer requiredReviewCount() {
		return requiredReviewCount;
	}

	public Set<CodeScanningToolArgs> requiredCodeScanning() {
		return requiredCodeScanning;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		RulesetArgs that = (RulesetArgs) o;
		return requiredLinearHistory == that.requiredLinearHistory
				&& noForcePushes == that.noForcePushes
				&& Objects.equals(name, that.name)
				&& Objects.equals(includePatterns, that.includePatterns)
				&& Objects
						.equals(requiredStatusChecks, that.requiredStatusChecks)
				&& Objects.equals(requiredReviewCount, that.requiredReviewCount)
				&& Objects.equals(
						requiredCodeScanning,
						that.requiredCodeScanning
				);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				name,
				includePatterns,
				requiredLinearHistory,
				noForcePushes,
				requiredStatusChecks,
				requiredReviewCount,
				requiredCodeScanning
		);
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static final class Builder {

		private String name;
		private List<String> includePatterns = List.of();
		private boolean requiredLinearHistory = false;
		private boolean noForcePushes = false;
		private Set<StatusCheckArgs> requiredStatusChecks = Set.of();
		private Integer requiredReviewCount = null;
		private Set<CodeScanningToolArgs> requiredCodeScanning = Set.of();

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
			this.requiredCodeScanning = rulesetArgs.requiredCodeScanning;
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

		public Builder requiredStatusChecks(
				StatusCheckArgs... statusCheckArgs
		) {
			this.requiredStatusChecks = Set.of(statusCheckArgs);
			return this;
		}

		public Builder addRequiredStatusChecks(
				StatusCheckArgs... statusCheckArgs
		) {
			Set<StatusCheckArgs> combined = new HashSet<>(
					this.requiredStatusChecks
			);
			combined.addAll(List.of(statusCheckArgs));
			this.requiredStatusChecks = combined;
			return this;
		}

		public Builder requiredReviewCount(Integer requiredReviewCount) {
			this.requiredReviewCount = requiredReviewCount;
			return this;
		}

		public Builder requiredCodeScanning(
				CodeScanningToolArgs... codeScanningToolArgs
		) {
			this.requiredCodeScanning = Set.of(codeScanningToolArgs);
			return this;
		}

		public Builder addRequiredCodeScanning(
				CodeScanningToolArgs... codeScanningToolArgs
		) {
			Set<CodeScanningToolArgs> combined = new HashSet<>(
					this.requiredCodeScanning
			);
			combined.addAll(List.of(codeScanningToolArgs));
			this.requiredCodeScanning = combined;
			return this;
		}

		public RulesetArgs build() {
			return new RulesetArgs(this);
		}

	}

}
