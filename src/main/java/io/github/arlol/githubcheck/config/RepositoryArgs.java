package io.github.arlol.githubcheck.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.WorkflowPermissions;

public final class RepositoryArgs {

	private final String name;
	private final boolean archived;
	private final PagesArgs pagesArgs;
	private final String description;
	private final String homepageUrl;
	private final RepositoryVisibility visibility;
	private final List<String> topics;
	private final List<String> actionsSecrets;
	private final Map<String, EnvironmentArgs> environments;
	private final List<RulesetArgs> rulesets;
	private final boolean immutableReleases;
	private final boolean allowRebaseMerge;
	private final boolean allowUpdateBranch;
	private final String defaultBranch;
	private final Map<String, BranchProtectionArgs> branchProtections;
	private final boolean vulnerabilityAlerts;
	private final boolean automatedSecurityFixes;
	private final boolean secretScanning;
	private final boolean secretScanningPushProtection;
	private final boolean secretScanningValidityChecks;
	private final boolean secretScanningNonProviderPatterns;
	private final boolean privateVulnerabilityReporting;
	private final boolean codeScanningDefaultSetup;
	private final boolean hasIssues;
	private final boolean hasProjects;
	private final boolean hasWiki;
	private final boolean allowMergeCommit;
	private final boolean allowSquashMerge;
	private final boolean allowAutoMerge;
	private final boolean deleteBranchOnMerge;
	private final WorkflowPermissions.DefaultWorkflowPermissions defaultWorkflowPermissions;
	private final boolean canApprovePullRequestReviews;

	private RepositoryArgs(Builder builder) {
		this.name = builder.name;
		this.archived = builder.archived;
		this.pagesArgs = builder.pagesArgs;
		this.description = builder.description;
		this.homepageUrl = builder.homepageUrl;
		this.visibility = builder.visibility;
		this.topics = List.copyOf(builder.topics);
		this.actionsSecrets = List.copyOf(builder.actionsSecrets);
		this.environments = Collections
				.unmodifiableMap(new LinkedHashMap<>(builder.environments));
		this.rulesets = List.copyOf(builder.rulesets);
		this.immutableReleases = builder.immutableReleases;
		this.allowRebaseMerge = builder.allowRebaseMerge;
		this.allowUpdateBranch = builder.allowUpdateBranch;
		this.defaultBranch = builder.defaultBranch;
		this.branchProtections = Map.copyOf(builder.branchProtections);
		this.vulnerabilityAlerts = builder.vulnerabilityAlerts;
		this.automatedSecurityFixes = builder.automatedSecurityFixes;
		this.secretScanning = builder.secretScanning;
		this.secretScanningPushProtection = builder.secretScanningPushProtection;
		this.secretScanningValidityChecks = builder.secretScanningValidityChecks;
		this.secretScanningNonProviderPatterns = builder.secretScanningNonProviderPatterns;
		this.privateVulnerabilityReporting = builder.privateVulnerabilityReporting;
		this.codeScanningDefaultSetup = builder.codeScanningDefaultSetup;
		this.hasIssues = builder.hasIssues;
		this.hasProjects = builder.hasProjects;
		this.hasWiki = builder.hasWiki;
		this.allowMergeCommit = builder.allowMergeCommit;
		this.allowSquashMerge = builder.allowSquashMerge;
		this.allowAutoMerge = builder.allowAutoMerge;
		this.deleteBranchOnMerge = builder.deleteBranchOnMerge;
		this.defaultWorkflowPermissions = builder.defaultWorkflowPermissions;
		this.canApprovePullRequestReviews = builder.canApprovePullRequestReviews;
	}

	public String name() {
		return name;
	}

	public boolean archived() {
		return archived;
	}

	public boolean pages() {
		return pagesArgs != null;
	}

	public PagesArgs pagesArgs() {
		return pagesArgs;
	}

	public String description() {
		return description;
	}

	public String homepageUrl() {
		return homepageUrl;
	}

	public RepositoryVisibility visibility() {
		return visibility;
	}

	public List<String> topics() {
		return topics;
	}

	public List<String> actionsSecrets() {
		return actionsSecrets;
	}

	public Map<String, EnvironmentArgs> environments() {
		return environments;
	}

	public List<RulesetArgs> rulesets() {
		return rulesets;
	}

	public boolean immutableReleases() {
		return immutableReleases;
	}

	public boolean allowRebaseMerge() {
		return allowRebaseMerge;
	}

	public boolean allowUpdateBranch() {
		return allowUpdateBranch;
	}

	public String defaultBranch() {
		return defaultBranch;
	}

	public Map<String, BranchProtectionArgs> branchProtections() {
		return branchProtections;
	}

	public boolean vulnerabilityAlerts() {
		return vulnerabilityAlerts;
	}

	public boolean automatedSecurityFixes() {
		return automatedSecurityFixes;
	}

	public boolean secretScanning() {
		return secretScanning;
	}

	public boolean secretScanningPushProtection() {
		return secretScanningPushProtection;
	}

	public boolean secretScanningValidityChecks() {
		return secretScanningValidityChecks;
	}

	public boolean secretScanningNonProviderPatterns() {
		return secretScanningNonProviderPatterns;
	}

	public boolean privateVulnerabilityReporting() {
		return privateVulnerabilityReporting;
	}

	public boolean codeScanningDefaultSetup() {
		return codeScanningDefaultSetup;
	}

	public boolean hasIssues() {
		return hasIssues;
	}

	public boolean hasProjects() {
		return hasProjects;
	}

	public boolean hasWiki() {
		return hasWiki;
	}

	public boolean allowMergeCommit() {
		return allowMergeCommit;
	}

	public boolean allowSquashMerge() {
		return allowSquashMerge;
	}

	public boolean allowAutoMerge() {
		return allowAutoMerge;
	}

	public boolean deleteBranchOnMerge() {
		return deleteBranchOnMerge;
	}

	public WorkflowPermissions.DefaultWorkflowPermissions defaultWorkflowPermissions() {
		return defaultWorkflowPermissions;
	}

	public boolean canApprovePullRequestReviews() {
		return canApprovePullRequestReviews;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		RepositoryArgs that = (RepositoryArgs) o;
		return archived == that.archived
				&& immutableReleases == that.immutableReleases
				&& allowRebaseMerge == that.allowRebaseMerge
				&& allowUpdateBranch == that.allowUpdateBranch
				&& vulnerabilityAlerts == that.vulnerabilityAlerts
				&& automatedSecurityFixes == that.automatedSecurityFixes
				&& secretScanning == that.secretScanning
				&& secretScanningPushProtection == that.secretScanningPushProtection
				&& secretScanningValidityChecks == that.secretScanningValidityChecks
				&& secretScanningNonProviderPatterns == that.secretScanningNonProviderPatterns
				&& privateVulnerabilityReporting == that.privateVulnerabilityReporting
				&& codeScanningDefaultSetup == that.codeScanningDefaultSetup
				&& hasIssues == that.hasIssues
				&& hasProjects == that.hasProjects && hasWiki == that.hasWiki
				&& allowMergeCommit == that.allowMergeCommit
				&& allowSquashMerge == that.allowSquashMerge
				&& allowAutoMerge == that.allowAutoMerge
				&& deleteBranchOnMerge == that.deleteBranchOnMerge
				&& canApprovePullRequestReviews == that.canApprovePullRequestReviews
				&& defaultWorkflowPermissions == that.defaultWorkflowPermissions
				&& Objects.equals(name, that.name)
				&& Objects.equals(pagesArgs, that.pagesArgs)
				&& Objects.equals(description, that.description)
				&& Objects.equals(homepageUrl, that.homepageUrl)
				&& Objects.equals(visibility, that.visibility)
				&& Objects.equals(topics, that.topics)
				&& Objects.equals(actionsSecrets, that.actionsSecrets)
				&& Objects.equals(environments, that.environments)
				&& Objects.equals(rulesets, that.rulesets)
				&& Objects.equals(defaultBranch, that.defaultBranch)
				&& Objects.equals(branchProtections, that.branchProtections);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
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
				allowMergeCommit,
				allowSquashMerge,
				allowAutoMerge,
				deleteBranchOnMerge,
				defaultWorkflowPermissions,
				canApprovePullRequestReviews
		);
	}

	public static Builder create(String name) {
		return new Builder(name);
	}

	public static RepositoryArgs archived(String name) {
		return new Builder(name).archived().build();
	}

	public static final class Builder {

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
		private boolean allowMergeCommit = true;
		private boolean allowSquashMerge = true;
		private boolean allowAutoMerge = false;
		private boolean deleteBranchOnMerge = false;
		private WorkflowPermissions.DefaultWorkflowPermissions defaultWorkflowPermissions = WorkflowPermissions.DefaultWorkflowPermissions.WRITE;
		private boolean canApprovePullRequestReviews = true;

		public Builder(String name) {
			this.name = name;
		}

		public Builder(RepositoryArgs repositoryArgs) {
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
			this.allowMergeCommit = repositoryArgs.allowMergeCommit;
			this.allowSquashMerge = repositoryArgs.allowSquashMerge;
			this.allowAutoMerge = repositoryArgs.allowAutoMerge;
			this.deleteBranchOnMerge = repositoryArgs.deleteBranchOnMerge;
			this.defaultWorkflowPermissions = repositoryArgs.defaultWorkflowPermissions;
			this.canApprovePullRequestReviews = repositoryArgs.canApprovePullRequestReviews;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder archived() {
			this.archived = true;
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
			return new RepositoryArgs(this);
		}

	}

}
