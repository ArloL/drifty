package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.drift.ActionSecretsDriftGroup;
import io.github.arlol.githubcheck.drift.ArchivedDriftGroup;
import io.github.arlol.githubcheck.drift.AutomatedSecurityFixesDriftGroup;
import io.github.arlol.githubcheck.drift.BranchProtectionDriftGroup;
import io.github.arlol.githubcheck.drift.CodeScanningDefaultSetupDriftGroup;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
import io.github.arlol.githubcheck.drift.EnvironmentConfigDriftGroup;
import io.github.arlol.githubcheck.drift.EnvironmentSecretsDriftGroup;
import io.github.arlol.githubcheck.drift.FixResult;
import io.github.arlol.githubcheck.drift.ImmutableReleasesDriftGroup;
import io.github.arlol.githubcheck.drift.PagesDriftGroup;
import io.github.arlol.githubcheck.drift.PrivateVulnerabilityReportingDriftGroup;
import io.github.arlol.githubcheck.drift.RepoSettingsDriftGroup;
import io.github.arlol.githubcheck.drift.RulesetDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningNonProviderPatternsDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningPushProtectionDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningValidityChecksDriftGroup;
import io.github.arlol.githubcheck.drift.TopicsDriftGroup;
import io.github.arlol.githubcheck.drift.VulnerabilityAlertsDriftGroup;
import io.github.arlol.githubcheck.drift.WorkflowPermissionsDriftGroup;

public class OrgChecker {

	private final GitHubClient client;
	private final String org;
	private final boolean fix;
	private final Map<String, String> githubSecrets;

	public OrgChecker(String token, String org) {
		this(new GitHubClient(token), org, false, Map.of());
	}

	public OrgChecker(String token, String org, boolean fix) {
		this(new GitHubClient(token), org, fix, Map.of());
	}

	public OrgChecker(
			String token,
			String org,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this(new GitHubClient(token), org, fix, githubSecrets);
	}

	OrgChecker(GitHubClient client, String org) {
		this(client, org, false, Map.of());
	}

	OrgChecker(GitHubClient client, String org, boolean fix) {
		this(client, org, fix, Map.of());
	}

	OrgChecker(
			GitHubClient client,
			String org,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this.client = client;
		this.org = org;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
	}

	public CheckResult check(List<RepositoryArgs> repositories)
			throws IOException, InterruptedException, ExecutionException {
		System.out.println("Fetching repo list for org: " + org);
		List<RepositorySummaryResponse> summaries = client.listOrgRepos(org);
		System.out.printf(
				"Found %d repos. Fetching details in parallel...%n",
				summaries.size()
		);

		long startFetch = System.currentTimeMillis();

		Map<String, RepositoryArgs> desiredByName = repositories.stream()
				.collect(Collectors.toMap(RepositoryArgs::name, r -> r));

		List<CheckResult.RepoCheckResult> results = new ArrayList<>();

		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<CheckResult.RepoCheckResult>> futures = summaries
					.stream()
					.map(
							summary -> executor.submit(
									() -> checkOne(summary, desiredByName)
							)
					)
					.toList();
			for (Future<CheckResult.RepoCheckResult> f : futures) {
				results.add(f.get());
			}
		}

		// Repos declared in config but not found in the org
		Set<String> foundNames = summaries.stream()
				.map(RepositorySummaryResponse::name)
				.collect(Collectors.toSet());
		repositories.stream()
				.filter(r -> !foundNames.contains(r.name()))
				.map(r -> CheckResult.RepoCheckResult.missing(r.name()))
				.forEach(results::add);

		double fetchSeconds = (System.currentTimeMillis() - startFetch)
				/ 1000.0;
		System.out.printf("Fetch complete in %.2f seconds%n%n", fetchSeconds);

		return new CheckResult(Collections.unmodifiableList(results));
	}

	private CheckResult.RepoCheckResult checkOne(
			RepositorySummaryResponse summary,
			Map<String, RepositoryArgs> desiredByName
	) {
		String name = summary.name();
		RepositoryArgs desired = desiredByName.get(name);
		if (desired == null) {
			return CheckResult.RepoCheckResult.unknown(name);
		}
		try {
			RepositoryState state = fetchState(summary);

			Map<DriftGroup, List<DriftFix>> groupDrifts = computeGroupDrifts(
					state,
					desired
			);

			List<String> diffs = groupDrifts.values()
					.stream()
					.flatMap(List::stream)
					.flatMap(driftFix -> driftFix.items().stream())
					.map(DriftItem::message)
					.collect(Collectors.toCollection(ArrayList::new));

			if (fix) {
				diffs = applyFixes(name, diffs, groupDrifts);
			}
			return diffs.isEmpty() ? CheckResult.RepoCheckResult.ok(name)
					: CheckResult.RepoCheckResult.drift(name, diffs);
		} catch (IOException | InterruptedException e) {
			return CheckResult.RepoCheckResult.error(name, e.getMessage());
		}
	}

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	RepositoryState fetchState(RepositorySummaryResponse summary)
			throws IOException, InterruptedException {
		String name = summary.name();
		boolean archived = summary.archived();

		var details = client.getRepo(org, name);

		boolean vulnAlerts = false;
		boolean automatedSecurityFixes = false;
		boolean immutableReleases = false;
		boolean privateVulnerabilityReporting = false;
		boolean codeScanningDefaultSetup = false;
		boolean secretScanning = false;
		boolean secretScanningPushProtection = false;
		boolean secretScanningNonProviderPatterns = false;
		boolean secretScanningValidityChecks = false;
		if (!archived) {
			vulnAlerts = client.getVulnerabilityAlerts(org, name);
			automatedSecurityFixes = client
					.getAutomatedSecurityFixes(org, name);
			var ir = client.getImmutableReleases(org, name);
			if (ir.isPresent()) {
				immutableReleases = ir.orElseThrow().enabled();
			}
			privateVulnerabilityReporting = client
					.getPrivateVulnerabilityReporting(org, name);
			codeScanningDefaultSetup = client
					.getCodeScanningDefaultSetup(org, name);
			var sa = details.securityAndAnalysis();
			if (sa != null) {
				if (sa.secretScanning() != null && sa.secretScanning()
						.status() == SecurityAndAnalysis.StatusObject.Status.ENABLED) {
					secretScanning = true;
				}
				if (sa.secretScanningPushProtection() != null && sa
						.secretScanningPushProtection()
						.status() == SecurityAndAnalysis.StatusObject.Status.ENABLED) {
					secretScanningPushProtection = true;
				}
				if (sa.secretScanningNonProviderPatterns() != null && sa
						.secretScanningNonProviderPatterns()
						.status() == SecurityAndAnalysis.StatusObject.Status.ENABLED) {
					secretScanningNonProviderPatterns = true;
				}
				if (sa.secretScanningValidityChecks() != null && sa
						.secretScanningValidityChecks()
						.status() == SecurityAndAnalysis.StatusObject.Status.ENABLED) {
					secretScanningValidityChecks = true;
				}
			}
		}

		Map<String, BranchProtectionResponse> branchProtections = new HashMap<>();
		if (!archived && RepositoryVisibility.PUBLIC == summary.visibility()) {
			var branches = client.getBranches(org, name, true);
			for (var branch : branches) {
				var bp = client.getBranchProtection(org, name, branch.name());
				branchProtections.put(branch.name(), bp.orElseThrow());
			}
		}

		List<String> secretNames = client.getActionSecretNames(org, name);
		List<EnvironmentDetailsResponse> environments = client
				.getEnvironments(org, name);

		Map<String, List<String>> envSecrets = new LinkedHashMap<>();
		Map<String, EnvironmentDetailsResponse> envDetails = new LinkedHashMap<>();
		for (EnvironmentDetailsResponse env : environments) {
			envDetails.put(env.name(), env);
			envSecrets.put(
					env.name(),
					client.getEnvironmentSecretNames(org, name, env.name())
			);
		}

		WorkflowPermissions wfPerms = client.getWorkflowPermissions(org, name);

		List<RulesetDetailsResponse> rulesets;
		if (archived) {
			rulesets = List.of();
		} else {
			var rulesetSummaries = client.listRulesets(org, name);
			rulesets = new ArrayList<>();
			for (var rs : rulesetSummaries) {
				rulesets.add(client.getRuleset(org, name, rs.id()));
			}
		}

		Optional<PagesResponse> pages = archived ? Optional.empty()
				: client.getPages(org, name);

		return new RepositoryState(
				name,
				summary,
				details,
				vulnAlerts,
				automatedSecurityFixes,
				branchProtections,
				secretNames,
				envSecrets,
				wfPerms,
				rulesets,
				pages,
				envDetails,
				immutableReleases,
				privateVulnerabilityReporting,
				codeScanningDefaultSetup,
				secretScanning,
				secretScanningPushProtection,
				secretScanningNonProviderPatterns,
				secretScanningValidityChecks
		);
	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	Map<DriftGroup, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			RepositoryArgs desired
	) {
		Map<DriftGroup, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
		for (var group : createDriftGroups(actual, desired)) {
			var fixes = group.detect();
			if (!fixes.isEmpty()) {
				groupDrifts.put(group, fixes);
			}
		}
		return groupDrifts;
	}

	List<DriftGroup> createDriftGroups(
			RepositoryState actual,
			RepositoryArgs desired
	) {
		if (desired.archived()) {
			// When archiving (or already archived): only check archived state,
			// skip all other groups since settings don't matter for archived
			// repos.
			return List.of(
					new ArchivedDriftGroup(
							true,
							actual.summary().archived(),
							client,
							org,
							actual.summary().name()
					)
			);
		}

		var groups = new ArrayList<DriftGroup>();

		// Always first: when actual.archived=true, unarchive must run before
		// any
		// other fix (other fixes fail on archived repos). When
		// actual.archived=false,
		// detect() returns empty and computeGroupDrifts skips it.
		groups.add(
				new ArchivedDriftGroup(
						false,
						actual.summary().archived(),
						client,
						org,
						actual.summary().name()
				)
		);

		groups.add(
				new RepoSettingsDriftGroup(
						desired,
						actual.details(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new TopicsDriftGroup(
						desired.topics(),
						actual.details().topics() != null
								? actual.details().topics()
								: List.of(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new WorkflowPermissionsDriftGroup(
						desired,
						actual.workflowPermissions(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new PagesDriftGroup(
						desired,
						actual.pages(),
						client,
						org,
						actual.summary().name()
				)
		);

		// Environment config
		groups.add(
				new EnvironmentConfigDriftGroup(
						desired,
						actual.environmentDetails(),
						client,
						org,
						actual.summary().name()
				)
		);

		// Secrets
		groups.add(
				new ActionSecretsDriftGroup(
						desired,
						new HashSet<>(actual.actionSecretNames()),
						githubSecrets,
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new EnvironmentSecretsDriftGroup(
						desired,
						actual.environmentSecretNames(),
						githubSecrets,
						client,
						org,
						actual.summary().name()
				)
		);

		// Security micro-groups
		groups.add(
				new VulnerabilityAlertsDriftGroup(
						desired,
						actual.vulnerabilityAlerts(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new AutomatedSecurityFixesDriftGroup(
						desired,
						actual.automatedSecurityFixes(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new ImmutableReleasesDriftGroup(
						desired,
						actual.immutableReleases(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningDriftGroup(
						desired,
						actual.details().securityAndAnalysis() != null
								&& actual.details()
										.securityAndAnalysis()
										.secretScanning() != null
								&& SecurityAndAnalysis.StatusObject.Status.ENABLED
										.equals(
												actual.details()
														.securityAndAnalysis()
														.secretScanning()
														.status()
										),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningPushProtectionDriftGroup(
						desired,
						actual.details().securityAndAnalysis() != null
								&& actual.details()
										.securityAndAnalysis()
										.secretScanningPushProtection() != null
								&& SecurityAndAnalysis.StatusObject.Status.ENABLED
										.equals(
												actual.details()
														.securityAndAnalysis()
														.secretScanningPushProtection()
														.status()
										),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new PrivateVulnerabilityReportingDriftGroup(
						desired,
						actual.privateVulnerabilityReporting(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new CodeScanningDefaultSetupDriftGroup(
						desired,
						actual.codeScanningDefaultSetup(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningNonProviderPatternsDriftGroup(
						desired,
						actual.secretScanningNonProviderPatterns(),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningValidityChecksDriftGroup(
						desired,
						actual.secretScanningValidityChecks(),
						client,
						org,
						actual.summary().name()
				)
		);

		// Branch protection
		groups.add(
				new BranchProtectionDriftGroup(
						desired,
						actual.branchProtections(),
						client,
						org,
						actual.summary().name()
				)
		);

		// Rulesets
		groups.add(
				new RulesetDriftGroup(
						desired,
						actual.rulesets(),
						client,
						org,
						actual.summary().name()
				)
		);

		return groups;
	}

	// ─── Fix
	// ──────────────────────────────────────────────────────────────

	List<String> applyFixes(
			String name,
			List<String> diffs,
			Map<DriftGroup, List<DriftFix>> groupDrifts
	) {
		List<String> remaining = new ArrayList<>(diffs);
		for (var fixes : groupDrifts.values()) {
			for (var driftFix : fixes) {
				var msgs = driftFix.items()
						.stream()
						.map(DriftItem::message)
						.toList();
				if (msgs.isEmpty()) {
					continue;
				}
				FixResult fixResult;
				try {
					fixResult = driftFix.fix().execute();
				} catch (RuntimeException e) {
					continue;
				}
				var unfixedMsgs = fixResult.unfixedItems()
						.stream()
						.map(DriftItem::message)
						.collect(Collectors.toSet());
				var fixedMsgs = msgs.stream()
						.filter(m -> !unfixedMsgs.contains(m))
						.toList();
				remaining.removeAll(fixedMsgs);
			}
		}
		return remaining;
	}

	// ─── Report
	// ──────────────────────────────────────────────────────────────

	public void printReport(CheckResult result) {
		List<CheckResult.RepoCheckResult> sorted = result.repos()
				.stream()
				.sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
				.toList();

		for (CheckResult.RepoCheckResult r : sorted) {
			switch (r.status()) {
			case OK -> System.out.printf("[OK]      %s%n", r.name());
			case DRIFT -> {
				System.out.printf("[DRIFT]   %s:%n", r.name());
				r.diffs()
						.forEach(d -> System.out.printf("            %s%n", d));
			}
			case ERROR ->
				System.out.printf("[ERROR]   %s: %s%n", r.name(), r.error());
			case UNKNOWN -> System.out
					.printf("[UNKNOWN] %s: not in desired config%n", r.name());
			case MISSING -> System.out.printf(
					"[MISSING] %s: in config but not found in org%n",
					r.name()
			);
			}
		}

		System.out.println();
		System.out.println("=== Summary ===");
		System.out.printf("Repos checked:  %d%n", result.repos().size());
		System.out.printf("OK:             %d%n", result.okCount());
		System.out.printf("Drifted:        %d%n", result.driftCount());
		System.out.printf("Errored:        %d%n", result.errorCount());
		System.out.printf("Unknown:        %d%n", result.unknownCount());
	}

}
