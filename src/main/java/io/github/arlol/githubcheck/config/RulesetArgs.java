package io.github.arlol.githubcheck.config;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RulesetArgs {

	private final String name;
	private final List<String> includePatterns;
	private final boolean requiredLinearHistory;
	private final boolean noForcePushes;
	private final Set<StatusCheckArgs> requiredStatusChecks;
	private final Integer requiredReviewCount;
	private final Set<CodeScanningToolArgs> requiredCodeScanning;
	private final boolean creation;
	private final boolean deletion;
	private final boolean requiredSignatures;
	private final boolean update;
	private final boolean updateAllowsFetchAndMerge;
	private final RulePatternArgs commitMessagePattern;
	private final RulePatternArgs commitAuthorEmailPattern;
	private final RulePatternArgs committerEmailPattern;
	private final RulePatternArgs branchNamePattern;
	private final RulePatternArgs tagNamePattern;
	private final Set<String> requiredDeployments;
	private final List<BypassActorArgs> bypassActors;

	private RulesetArgs(Builder builder) {
		this.name = builder.name;
		this.includePatterns = List.copyOf(builder.includePatterns);
		this.requiredLinearHistory = builder.requiredLinearHistory;
		this.noForcePushes = builder.noForcePushes;
		this.requiredStatusChecks = Set.copyOf(builder.requiredStatusChecks);
		this.requiredReviewCount = builder.requiredReviewCount;
		this.requiredCodeScanning = Set.copyOf(builder.requiredCodeScanning);
		this.creation = builder.creation;
		this.deletion = builder.deletion;
		this.requiredSignatures = builder.requiredSignatures;
		this.update = builder.update;
		this.updateAllowsFetchAndMerge = builder.updateAllowsFetchAndMerge;
		this.commitMessagePattern = builder.commitMessagePattern;
		this.commitAuthorEmailPattern = builder.commitAuthorEmailPattern;
		this.committerEmailPattern = builder.committerEmailPattern;
		this.branchNamePattern = builder.branchNamePattern;
		this.tagNamePattern = builder.tagNamePattern;
		this.requiredDeployments = Set.copyOf(builder.requiredDeployments);
		this.bypassActors = List.copyOf(builder.bypassActors);
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

	public boolean creation() {
		return creation;
	}

	public boolean deletion() {
		return deletion;
	}

	public boolean requiredSignatures() {
		return requiredSignatures;
	}

	public boolean update() {
		return update;
	}

	public boolean updateAllowsFetchAndMerge() {
		return updateAllowsFetchAndMerge;
	}

	public RulePatternArgs commitMessagePattern() {
		return commitMessagePattern;
	}

	public RulePatternArgs commitAuthorEmailPattern() {
		return commitAuthorEmailPattern;
	}

	public RulePatternArgs committerEmailPattern() {
		return committerEmailPattern;
	}

	public RulePatternArgs branchNamePattern() {
		return branchNamePattern;
	}

	public RulePatternArgs tagNamePattern() {
		return tagNamePattern;
	}

	public Set<String> requiredDeployments() {
		return requiredDeployments;
	}

	public List<BypassActorArgs> bypassActors() {
		return bypassActors;
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
				&& creation == that.creation && deletion == that.deletion
				&& requiredSignatures == that.requiredSignatures
				&& update == that.update
				&& updateAllowsFetchAndMerge == that.updateAllowsFetchAndMerge
				&& Objects.equals(name, that.name)
				&& Objects.equals(includePatterns, that.includePatterns)
				&& Objects
						.equals(requiredStatusChecks, that.requiredStatusChecks)
				&& Objects.equals(requiredReviewCount, that.requiredReviewCount)
				&& Objects
						.equals(requiredCodeScanning, that.requiredCodeScanning)
				&& Objects
						.equals(commitMessagePattern, that.commitMessagePattern)
				&& Objects.equals(
						commitAuthorEmailPattern,
						that.commitAuthorEmailPattern
				)
				&& Objects.equals(
						committerEmailPattern,
						that.committerEmailPattern
				) && Objects.equals(branchNamePattern, that.branchNamePattern)
				&& Objects.equals(tagNamePattern, that.tagNamePattern)
				&& Objects.equals(requiredDeployments, that.requiredDeployments)
				&& Objects.equals(bypassActors, that.bypassActors);
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
				requiredCodeScanning,
				creation,
				deletion,
				requiredSignatures,
				update,
				updateAllowsFetchAndMerge,
				commitMessagePattern,
				commitAuthorEmailPattern,
				committerEmailPattern,
				branchNamePattern,
				tagNamePattern,
				requiredDeployments,
				bypassActors
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
		private boolean creation = false;
		private boolean deletion = false;
		private boolean requiredSignatures = false;
		private boolean update = false;
		private boolean updateAllowsFetchAndMerge = false;
		private RulePatternArgs commitMessagePattern = null;
		private RulePatternArgs commitAuthorEmailPattern = null;
		private RulePatternArgs committerEmailPattern = null;
		private RulePatternArgs branchNamePattern = null;
		private RulePatternArgs tagNamePattern = null;
		private Set<String> requiredDeployments = Set.of();
		private List<BypassActorArgs> bypassActors = List.of();

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
			this.creation = rulesetArgs.creation;
			this.deletion = rulesetArgs.deletion;
			this.requiredSignatures = rulesetArgs.requiredSignatures;
			this.update = rulesetArgs.update;
			this.updateAllowsFetchAndMerge = rulesetArgs.updateAllowsFetchAndMerge;
			this.commitMessagePattern = rulesetArgs.commitMessagePattern;
			this.commitAuthorEmailPattern = rulesetArgs.commitAuthorEmailPattern;
			this.committerEmailPattern = rulesetArgs.committerEmailPattern;
			this.branchNamePattern = rulesetArgs.branchNamePattern;
			this.tagNamePattern = rulesetArgs.tagNamePattern;
			this.requiredDeployments = rulesetArgs.requiredDeployments;
			this.bypassActors = rulesetArgs.bypassActors;
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

		public Builder creation(boolean creation) {
			this.creation = creation;
			return this;
		}

		public Builder deletion(boolean deletion) {
			this.deletion = deletion;
			return this;
		}

		public Builder requiredSignatures(boolean requiredSignatures) {
			this.requiredSignatures = requiredSignatures;
			return this;
		}

		public Builder update(boolean update) {
			this.update = update;
			return this;
		}

		public Builder updateAllowsFetchAndMerge(
				boolean updateAllowsFetchAndMerge
		) {
			this.updateAllowsFetchAndMerge = updateAllowsFetchAndMerge;
			return this;
		}

		public Builder commitMessagePattern(
				RulePatternArgs commitMessagePattern
		) {
			this.commitMessagePattern = commitMessagePattern;
			return this;
		}

		public Builder commitAuthorEmailPattern(
				RulePatternArgs commitAuthorEmailPattern
		) {
			this.commitAuthorEmailPattern = commitAuthorEmailPattern;
			return this;
		}

		public Builder committerEmailPattern(
				RulePatternArgs committerEmailPattern
		) {
			this.committerEmailPattern = committerEmailPattern;
			return this;
		}

		public Builder branchNamePattern(RulePatternArgs branchNamePattern) {
			this.branchNamePattern = branchNamePattern;
			return this;
		}

		public Builder tagNamePattern(RulePatternArgs tagNamePattern) {
			this.tagNamePattern = tagNamePattern;
			return this;
		}

		public Builder requiredDeployments(String... environments) {
			this.requiredDeployments = Set.of(environments);
			return this;
		}

		public Builder bypassActors(BypassActorArgs... actors) {
			this.bypassActors = List.of(actors);
			return this;
		}

		public RulesetArgs build() {
			return new RulesetArgs(this);
		}

	}

}
