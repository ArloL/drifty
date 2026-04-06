package io.github.arlol.githubcheck.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions;

public record RepositoryArgs(
		String owner,
		String name,
		boolean archived,
		PagesArgs pagesArgs,
		String description,
		String homepageUrl,
		RepositoryVisibility visibility,
		List<String> topics,
		List<String> actionsSecrets,
		Map<String, EnvironmentArgs> environments,
		List<RulesetArgs> rulesets,
		boolean immutableReleases,
		boolean allowRebaseMerge,
		boolean allowUpdateBranch,
		String defaultBranch,
		Map<String, BranchProtectionArgs> branchProtections,
		boolean vulnerabilityAlerts,
		boolean automatedSecurityFixes,
		boolean secretScanning,
		boolean secretScanningPushProtection,
		boolean secretScanningValidityChecks,
		boolean secretScanningNonProviderPatterns,
		boolean privateVulnerabilityReporting,
		boolean codeScanningDefaultSetup,
		boolean hasIssues,
		boolean hasProjects,
		boolean hasWiki,
		boolean hasDiscussions,
		boolean isTemplate,
		boolean allowForking,
		boolean webCommitSignoffRequired,
		boolean allowMergeCommit,
		boolean allowSquashMerge,
		boolean allowAutoMerge,
		boolean deleteBranchOnMerge,
		SquashMergeCommitTitle squashMergeCommitTitle,
		SquashMergeCommitMessage squashMergeCommitMessage,
		MergeCommitTitle mergeCommitTitle,
		MergeCommitMessage mergeCommitMessage,
		WorkflowPermissions.DefaultWorkflowPermissions defaultWorkflowPermissions,
		boolean canApprovePullRequestReviews
) {

	public RepositoryArgs {
		topics = List.copyOf(topics);
		actionsSecrets = List.copyOf(actionsSecrets);
		environments = Collections
				.unmodifiableMap(new LinkedHashMap<>(environments));
		rulesets = List.copyOf(rulesets);
		branchProtections = Map.copyOf(branchProtections);
	}

	public boolean pages() {
		return pagesArgs != null;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	public static Builder create(String owner, String name) {
		return new Builder(owner, name);
	}

	public static final class Builder {

		private String owner;
		private String name;
		private boolean archived = false;
		private PagesArgs pagesArgs = null;
		private String description = "";
		private String homepageUrl = "";
		private RepositoryVisibility visibility = RepositoryVisibility.PUBLIC;
		private List<String> topics = List.of();
		private List<String> actionsSecrets = List.of();
		private final Map<String, EnvironmentArgs> environments = new LinkedHashMap<>();
		private List<RulesetArgs> rulesets = List.of();
		private boolean immutableReleases = false;
		private boolean allowRebaseMerge = true;
		private boolean allowUpdateBranch = false;
		private String defaultBranch = "main";
		private Map<String, BranchProtectionArgs> branchProtections = Map.of();
		private boolean vulnerabilityAlerts = true;
		private boolean automatedSecurityFixes = false;
		private boolean secretScanning = true;
		private boolean secretScanningPushProtection = true;
		private boolean secretScanningValidityChecks = false;
		private boolean secretScanningNonProviderPatterns = false;
		private boolean privateVulnerabilityReporting = false;
		private boolean codeScanningDefaultSetup = false;
		private boolean hasIssues = true;
		private boolean hasProjects = true;
		private boolean hasWiki = true;
		private boolean hasDiscussions = false;
		private boolean isTemplate = false;
		private boolean allowForking = true;
		private boolean webCommitSignoffRequired = false;
		private boolean allowMergeCommit = true;
		private boolean allowSquashMerge = true;
		private boolean allowAutoMerge = false;
		private boolean deleteBranchOnMerge = false;
		private SquashMergeCommitTitle squashMergeCommitTitle = SquashMergeCommitTitle.COMMIT_OR_PR_TITLE;
		private SquashMergeCommitMessage squashMergeCommitMessage = SquashMergeCommitMessage.COMMIT_MESSAGES;
		private MergeCommitTitle mergeCommitTitle = MergeCommitTitle.MERGE_MESSAGE;
		private MergeCommitMessage mergeCommitMessage = MergeCommitMessage.PR_TITLE;
		private WorkflowPermissions.DefaultWorkflowPermissions defaultWorkflowPermissions = WorkflowPermissions.DefaultWorkflowPermissions.WRITE;
		private boolean canApprovePullRequestReviews = true;

		public Builder(String owner, String name) {
			this.owner = owner;
			this.name = name;
		}

		public Builder(RepositoryArgs repositoryArgs) {
			this.owner = repositoryArgs.owner;
			this.name = repositoryArgs.name;
			this.archived = repositoryArgs.archived;
			this.pagesArgs = repositoryArgs.pagesArgs;
			this.description = repositoryArgs.description;
			this.homepageUrl = repositoryArgs.homepageUrl;
			this.visibility = repositoryArgs.visibility;
			this.topics = repositoryArgs.topics;
			this.actionsSecrets = repositoryArgs.actionsSecrets;
			this.environments.putAll(repositoryArgs.environments);
			this.rulesets = repositoryArgs.rulesets;
			this.immutableReleases = repositoryArgs.immutableReleases;
			this.allowRebaseMerge = repositoryArgs.allowRebaseMerge;
			this.allowUpdateBranch = repositoryArgs.allowUpdateBranch;
			this.defaultBranch = repositoryArgs.defaultBranch;
			this.branchProtections = repositoryArgs.branchProtections;
			this.vulnerabilityAlerts = repositoryArgs.vulnerabilityAlerts;
			this.automatedSecurityFixes = repositoryArgs.automatedSecurityFixes;
			this.secretScanning = repositoryArgs.secretScanning;
			this.secretScanningPushProtection = repositoryArgs.secretScanningPushProtection;
			this.secretScanningValidityChecks = repositoryArgs.secretScanningValidityChecks;
			this.secretScanningNonProviderPatterns = repositoryArgs.secretScanningNonProviderPatterns;
			this.privateVulnerabilityReporting = repositoryArgs.privateVulnerabilityReporting;
			this.codeScanningDefaultSetup = repositoryArgs.codeScanningDefaultSetup;
			this.hasIssues = repositoryArgs.hasIssues;
			this.hasProjects = repositoryArgs.hasProjects;
			this.hasWiki = repositoryArgs.hasWiki;
			this.hasDiscussions = repositoryArgs.hasDiscussions;
			this.isTemplate = repositoryArgs.isTemplate;
			this.allowForking = repositoryArgs.allowForking;
			this.webCommitSignoffRequired = repositoryArgs.webCommitSignoffRequired;
			this.allowMergeCommit = repositoryArgs.allowMergeCommit;
			this.allowSquashMerge = repositoryArgs.allowSquashMerge;
			this.allowAutoMerge = repositoryArgs.allowAutoMerge;
			this.deleteBranchOnMerge = repositoryArgs.deleteBranchOnMerge;
			this.squashMergeCommitTitle = repositoryArgs.squashMergeCommitTitle;
			this.squashMergeCommitMessage = repositoryArgs.squashMergeCommitMessage;
			this.mergeCommitTitle = repositoryArgs.mergeCommitTitle;
			this.mergeCommitMessage = repositoryArgs.mergeCommitMessage;
			this.defaultWorkflowPermissions = repositoryArgs.defaultWorkflowPermissions;
			this.canApprovePullRequestReviews = repositoryArgs.canApprovePullRequestReviews;
		}

		public Builder owner(String owner) {
			this.owner = owner;
			return this;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder archived() {
			this.archived = true;
			return this;
		}

		public Builder archived(boolean archived) {
			this.archived = archived;
			return this;
		}

		public Builder pages() {
			this.pagesArgs = PagesArgs.workflow();
			return this;
		}

		public Builder pages(PagesArgs pagesArgs) {
			this.pagesArgs = pagesArgs;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder homepageUrl(String homepageUrl) {
			this.homepageUrl = homepageUrl;
			return this;
		}

		public Builder visibility(RepositoryVisibility visibility) {
			this.visibility = visibility;
			return this;
		}

		public Builder topics(String... topics) {
			this.topics = List.of(topics);
			return this;
		}

		public Builder actionsSecrets(String... secrets) {
			this.actionsSecrets = List.of(secrets);
			return this;
		}

		public Builder environment(String name, EnvironmentArgs args) {
			this.environments.put(name, args);
			return this;
		}

		public Builder environment(
				String name,
				Consumer<EnvironmentArgs.Builder> configure
		) {
			var envBuilder = EnvironmentArgs.builder(name);
			configure.accept(envBuilder);
			this.environments.put(name, envBuilder.build());
			return this;
		}

		public Builder rulesets(RulesetArgs... rulesets) {
			this.rulesets = List.of(rulesets);
			return this;
		}

		public Builder branchProtections(
				BranchProtectionArgs... branchProtections
		) {
			this.branchProtections = Stream.of(branchProtections)
					.collect(
							Collectors.toMap(
									BranchProtectionArgs::pattern,
									Function.identity()
							)
					);
			return this;
		}

		public Builder addBranchProtections(BranchProtectionArgs... args) {
			var branchProtections = new HashMap<>(this.branchProtections);
			for (var bpa : args) {
				branchProtections.put(bpa.pattern(), bpa);
			}
			this.branchProtections = branchProtections;
			return this;
		}

		public Builder updateBranchProtection(
				String pattern,
				Consumer<BranchProtectionArgs.Builder> consumer
		) {
			var branchProtections = new HashMap<>(this.branchProtections);
			var bpa = Optional.ofNullable(branchProtections.remove(pattern))
					.orElseThrow();
			var builder = bpa.toBuilder();
			consumer.accept(builder);
			branchProtections.put(bpa.pattern(), builder.build());
			this.branchProtections = branchProtections;
			return this;
		}

		public Builder immutableReleases(boolean immutableReleases) {
			this.immutableReleases = immutableReleases;
			return this;
		}

		public Builder allowRebaseMerge(boolean allowRebaseMerge) {
			this.allowRebaseMerge = allowRebaseMerge;
			return this;
		}

		public Builder allowUpdateBranch(boolean allowUpdateBranch) {
			this.allowUpdateBranch = allowUpdateBranch;
			return this;
		}

		public Builder defaultBranch(String defaultBranch) {
			this.defaultBranch = defaultBranch;
			return this;
		}

		public Builder vulnerabilityAlerts(boolean vulnerabilityAlerts) {
			this.vulnerabilityAlerts = vulnerabilityAlerts;
			return this;
		}

		public Builder automatedSecurityFixes(boolean automatedSecurityFixes) {
			this.automatedSecurityFixes = automatedSecurityFixes;
			return this;
		}

		public Builder secretScanning(boolean secretScanning) {
			this.secretScanning = secretScanning;
			return this;
		}

		public Builder secretScanningPushProtection(
				boolean secretScanningPushProtection
		) {
			this.secretScanningPushProtection = secretScanningPushProtection;
			return this;
		}

		public Builder secretScanningValidityChecks(
				boolean secretScanningValidityChecks
		) {
			this.secretScanningValidityChecks = secretScanningValidityChecks;
			return this;
		}

		public Builder secretScanningNonProviderPatterns(
				boolean secretScanningNonProviderPatterns
		) {
			this.secretScanningNonProviderPatterns = secretScanningNonProviderPatterns;
			return this;
		}

		public Builder privateVulnerabilityReporting(
				boolean privateVulnerabilityReporting
		) {
			this.privateVulnerabilityReporting = privateVulnerabilityReporting;
			return this;
		}

		public Builder codeScanningDefaultSetup(
				boolean codeScanningDefaultSetup
		) {
			this.codeScanningDefaultSetup = codeScanningDefaultSetup;
			return this;
		}

		public Builder hasIssues(boolean hasIssues) {
			this.hasIssues = hasIssues;
			return this;
		}

		public Builder hasProjects(boolean hasProjects) {
			this.hasProjects = hasProjects;
			return this;
		}

		public Builder hasWiki(boolean hasWiki) {
			this.hasWiki = hasWiki;
			return this;
		}

		public Builder hasDiscussions(boolean hasDiscussions) {
			this.hasDiscussions = hasDiscussions;
			return this;
		}

		public Builder isTemplate(boolean isTemplate) {
			this.isTemplate = isTemplate;
			return this;
		}

		public Builder allowForking(boolean allowForking) {
			this.allowForking = allowForking;
			return this;
		}

		public Builder webCommitSignoffRequired(
				boolean webCommitSignoffRequired
		) {
			this.webCommitSignoffRequired = webCommitSignoffRequired;
			return this;
		}

		public Builder allowMergeCommit(boolean allowMergeCommit) {
			this.allowMergeCommit = allowMergeCommit;
			return this;
		}

		public Builder allowSquashMerge(boolean allowSquashMerge) {
			this.allowSquashMerge = allowSquashMerge;
			return this;
		}

		public Builder allowAutoMerge(boolean allowAutoMerge) {
			this.allowAutoMerge = allowAutoMerge;
			return this;
		}

		public Builder deleteBranchOnMerge(boolean deleteBranchOnMerge) {
			this.deleteBranchOnMerge = deleteBranchOnMerge;
			return this;
		}

		public Builder squashMergeCommitTitle(
				SquashMergeCommitTitle squashMergeCommitTitle
		) {
			this.squashMergeCommitTitle = squashMergeCommitTitle;
			return this;
		}

		public Builder squashMergeCommitMessage(
				SquashMergeCommitMessage squashMergeCommitMessage
		) {
			this.squashMergeCommitMessage = squashMergeCommitMessage;
			return this;
		}

		public Builder mergeCommitTitle(MergeCommitTitle mergeCommitTitle) {
			this.mergeCommitTitle = mergeCommitTitle;
			return this;
		}

		public Builder mergeCommitMessage(
				MergeCommitMessage mergeCommitMessage
		) {
			this.mergeCommitMessage = mergeCommitMessage;
			return this;
		}

		public Builder defaultWorkflowPermissions(
				WorkflowPermissions.DefaultWorkflowPermissions defaultWorkflowPermissions
		) {
			this.defaultWorkflowPermissions = defaultWorkflowPermissions;
			return this;
		}

		public Builder canApprovePullRequestReviews(
				boolean canApprovePullRequestReviews
		) {
			this.canApprovePullRequestReviews = canApprovePullRequestReviews;
			return this;
		}

		public RepositoryArgs build() {
			return new RepositoryArgs(
					owner,
					name,
					archived,
					pagesArgs,
					description,
					homepageUrl,
					visibility,
					topics,
					actionsSecrets,
					environments,
					rulesets,
					immutableReleases,
					allowRebaseMerge,
					allowUpdateBranch,
					defaultBranch,
					branchProtections,
					vulnerabilityAlerts,
					automatedSecurityFixes,
					secretScanning,
					secretScanningPushProtection,
					secretScanningValidityChecks,
					secretScanningNonProviderPatterns,
					privateVulnerabilityReporting,
					codeScanningDefaultSetup,
					hasIssues,
					hasProjects,
					hasWiki,
					hasDiscussions,
					isTemplate,
					allowForking,
					webCommitSignoffRequired,
					allowMergeCommit,
					allowSquashMerge,
					allowAutoMerge,
					deleteBranchOnMerge,
					squashMergeCommitTitle,
					squashMergeCommitMessage,
					mergeCommitTitle,
					mergeCommitMessage,
					defaultWorkflowPermissions,
					canApprovePullRequestReviews
			);
		}

	}

}
