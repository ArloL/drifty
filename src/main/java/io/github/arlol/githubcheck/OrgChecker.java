package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.drift.ActionSecretsDriftGroup;
import io.github.arlol.githubcheck.drift.AdvancedSecurityDriftGroup;
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
import io.github.arlol.githubcheck.drift.SecretScanningAiDetectionDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningDelegatedAlertDismissalDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningDelegatedBypassDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningNonProviderPatternsDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningPushProtectionDriftGroup;
import io.github.arlol.githubcheck.drift.SecretScanningValidityChecksDriftGroup;
import io.github.arlol.githubcheck.drift.TopicsDriftGroup;
import io.github.arlol.githubcheck.drift.VulnerabilityAlertsDriftGroup;
import io.github.arlol.githubcheck.drift.WorkflowPermissionsDriftGroup;
import io.github.arlol.githubcheck.state.DriftyState;

public class OrgChecker {

	private final GitHubClient client;
	private final String org;
	private final boolean fix;
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

	public OrgChecker(String token, String org) {
		this(new GitHubClient(token), org, false, Map.of(), new DriftyState());
	}

	public OrgChecker(String token, String org, boolean fix) {
		this(new GitHubClient(token), org, fix, Map.of(), new DriftyState());
	}

	public OrgChecker(
			String token,
			String org,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this(
				new GitHubClient(token),
				org,
				fix,
				githubSecrets,
				new DriftyState()
		);
	}

	public OrgChecker(
			String token,
			String org,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this(new GitHubClient(token), org, fix, githubSecrets, state);
	}

	OrgChecker(GitHubClient client, String org) {
		this(client, org, false, Map.of(), new DriftyState());
	}

	OrgChecker(GitHubClient client, String org, boolean fix) {
		this(client, org, fix, Map.of(), new DriftyState());
	}

	OrgChecker(
			GitHubClient client,
			String org,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this(client, org, fix, githubSecrets, new DriftyState());
	}

	OrgChecker(
			GitHubClient client,
			String org,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this.client = client;
		this.org = org;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
	}

	public CheckResult check(List<Drifty.Repository> repositories)
			throws IOException, InterruptedException, ExecutionException {
		System.out.println("Fetching repo list for org: " + org);
		List<RepositorySummaryResponse> summaries = client.listOrgRepos(org);
		System.out.printf(
				"Found %d repos. Fetching details in parallel...%n",
				summaries.size()
		);

		long startFetch = System.currentTimeMillis();

		Map<String, Drifty.Repository> desiredByName = repositories.stream()
				.collect(Collectors.toMap(r -> r.name, r -> r));

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
				.filter(r -> !foundNames.contains(r.name))
				.map(r -> CheckResult.RepoCheckResult.missing(r.name))
				.forEach(results::add);

		double fetchSeconds = (System.currentTimeMillis() - startFetch)
				/ 1000.0;
		System.out.printf("Fetch complete in %.2f seconds%n%n", fetchSeconds);

		return new CheckResult(Collections.unmodifiableList(results));
	}

	private CheckResult.RepoCheckResult checkOne(
			RepositorySummaryResponse summary,
			Map<String, Drifty.Repository> desiredByName
	) {
		String name = summary.name();
		Drifty.Repository desired = desiredByName.get(name);
		if (desired == null) {
			return CheckResult.RepoCheckResult.unknown(name);
		}
		try {
			RepositoryState state = fetchState(summary);

			Map<DriftGroup, List<DriftFix>> groupDrifts = computeGroupDrifts(
					state,
					desired
			);

			if (fix) {
				FixOutcome outcome = applyFixes(name, groupDrifts);
				return CheckResult.RepoCheckResult.fixed(
						name,
						render(outcome.unfixedItems()),
						fixReports(outcome)
				);
			}

			List<String> diffs = groupDrifts.values()
					.stream()
					.flatMap(List::stream)
					.flatMap(driftFix -> driftFix.items().stream())
					.map(DriftItem::message)
					.collect(Collectors.toCollection(ArrayList::new));

			if (diffs.isEmpty()) {
				return CheckResult.RepoCheckResult.ok(name);
			}
			// In check mode, preview which groups --fix would act on.
			List<String> fixPreview = groupDrifts.keySet()
					.stream()
					.map(DriftGroup::name)
					.toList();
			return CheckResult.RepoCheckResult.drift(name, diffs, fixPreview);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return CheckResult.RepoCheckResult.error(name, e.getMessage());
		} catch (IOException e) {
			return CheckResult.RepoCheckResult.error(name, e.getMessage());
		}
	}

	private static List<String> render(List<DriftItem> items) {
		return items.stream().map(DriftItem::message).toList();
	}

	/**
	 * One FIXED/FAILED line per drift item, in the order the fixes ran.
	 */
	private static List<CheckResult.FixReport> fixReports(FixOutcome outcome) {
		var reports = new ArrayList<CheckResult.FixReport>();
		outcome.fixed()
				.forEach(
						item -> reports.add(
								new CheckResult.FixReport(
										item.path(),
										true,
										null
								)
						)
				);
		outcome.unfixed()
				.forEach(
						unfixed -> reports.add(
								new CheckResult.FixReport(
										unfixed.item().path(),
										false,
										unfixed.reason()
								)
						)
				);
		return reports;
	}

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	RepositoryState fetchState(RepositorySummaryResponse summary)
			throws IOException, InterruptedException {
		String name = summary.name();
		boolean archived = summary.archived();

		var details = client.getRepo(org, name);

		SecurityFlags security = archived ? SecurityFlags.NONE
				: fetchSecurityFlags(name, details.securityAndAnalysis());

		Map<String, BranchProtectionResponse> branchProtections = fetchBranchProtections(
				summary,
				name,
				archived
		);

		List<Secret> secrets = client.getActionSecrets(org, name);
		List<EnvironmentDetailsResponse> environments = client
				.getEnvironments(org, name);

		Map<String, List<Secret>> envSecrets = new LinkedHashMap<>();
		Map<String, EnvironmentDetailsResponse> envDetails = new LinkedHashMap<>();
		for (EnvironmentDetailsResponse env : environments) {
			envDetails.put(env.name(), env);
			envSecrets.put(
					env.name(),
					client.getEnvironmentSecrets(org, name, env.name())
			);
		}

		WorkflowPermissions wfPerms = client.getWorkflowPermissions(org, name);

		List<RulesetDetailsResponse> rulesets = archived ? List.of()
				: fetchRulesets(name);

		Optional<PagesResponse> pages = archived ? Optional.empty()
				: client.getPages(org, name);

		return new RepositoryState(
				name,
				summary,
				details,
				security.vulnAlerts(),
				security.automatedSecurityFixes(),
				branchProtections,
				secrets,
				envSecrets,
				wfPerms,
				rulesets,
				pages,
				envDetails,
				security.immutableReleases(),
				security.privateVulnerabilityReporting(),
				security.codeScanningDefaultSetup(),
				security.secretScanning(),
				security.secretScanningPushProtection(),
				security.secretScanningNonProviderPatterns(),
				security.secretScanningValidityChecks()
		);
	}

	private SecurityFlags fetchSecurityFlags(
			String name,
			SecurityAndAnalysis sa
	) {
		boolean vulnAlerts = client.getVulnerabilityAlerts(org, name);
		boolean automatedSecurityFixes = client
				.getAutomatedSecurityFixes(org, name);
		var immutableReleases = client.getImmutableReleases(org, name);
		boolean privateVulnerabilityReporting = client
				.getPrivateVulnerabilityReporting(org, name);
		boolean codeScanningDefaultSetup = client
				.getCodeScanningDefaultSetup(org, name);
		return new SecurityFlags(
				vulnAlerts,
				automatedSecurityFixes,
				immutableReleases.isPresent()
						&& immutableReleases.orElseThrow().enabled(),
				privateVulnerabilityReporting,
				codeScanningDefaultSetup,
				sa != null && isEnabled(sa.secretScanning()),
				sa != null && isEnabled(sa.secretScanningPushProtection()),
				sa != null && isEnabled(sa.secretScanningNonProviderPatterns()),
				sa != null && isEnabled(sa.secretScanningValidityChecks())
		);
	}

	private static boolean isEnabled(
			SecurityAndAnalysis.StatusObject statusObject
	) {
		return statusObject != null && statusObject
				.status() == SecurityAndAnalysis.StatusObject.Status.ENABLED;
	}

	private Map<String, BranchProtectionResponse> fetchBranchProtections(
			RepositorySummaryResponse summary,
			String name,
			boolean archived
	) {
		Map<String, BranchProtectionResponse> branchProtections = new HashMap<>();
		if (archived || RepositoryVisibility.PUBLIC != summary.visibility()) {
			return branchProtections;
		}
		for (var branch : client.getBranches(org, name, true)) {
			var bp = client.getBranchProtection(org, name, branch.name());
			branchProtections.put(branch.name(), bp.orElseThrow());
		}
		return branchProtections;
	}

	private List<RulesetDetailsResponse> fetchRulesets(String name) {
		var rulesets = new ArrayList<RulesetDetailsResponse>();
		for (var rs : client.listRulesets(org, name)) {
			rulesets.add(client.getRuleset(org, name, rs.id()));
		}
		return rulesets;
	}

	/**
	 * The security- and analysis-related repository flags, all {@code false}
	 * for an archived repository since GitHub does not expose them there.
	 */
	private record SecurityFlags(
			boolean vulnAlerts,
			boolean automatedSecurityFixes,
			boolean immutableReleases,
			boolean privateVulnerabilityReporting,
			boolean codeScanningDefaultSetup,
			boolean secretScanning,
			boolean secretScanningPushProtection,
			boolean secretScanningNonProviderPatterns,
			boolean secretScanningValidityChecks
	) {

		private static final SecurityFlags NONE = new SecurityFlags(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false
		);

	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	Map<DriftGroup, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
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

	private static boolean securityFlag(
			RepositoryState actual,
			Function<SecurityAndAnalysis, SecurityAndAnalysis.StatusObject> getter
	) {
		var sa = actual.details().securityAndAnalysis();
		if (sa == null) {
			return false;
		}
		var statusObject = getter.apply(sa);
		return statusObject != null && statusObject
				.status() == SecurityAndAnalysis.StatusObject.Status.ENABLED;
	}

	private static List<SecurityAndAnalysis.BypassReviewer> bypassReviewers(
			RepositoryState actual
	) {
		var sa = actual.details().securityAndAnalysis();
		if (sa == null || sa.secretScanningDelegatedBypassOptions() == null
				|| sa.secretScanningDelegatedBypassOptions()
						.reviewers() == null) {
			return List.of();
		}
		return sa.secretScanningDelegatedBypassOptions().reviewers();
	}

	List<DriftGroup> createDriftGroups(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		if (desired.archived) {
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
						desired.topics,
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
						actual.actionSecrets(),
						githubSecrets,
						state,
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new EnvironmentSecretsDriftGroup(
						desired,
						actual.environmentSecrets(),
						githubSecrets,
						state,
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
		groups.add(
				new AdvancedSecurityDriftGroup(
						desired,
						securityFlag(
								actual,
								SecurityAndAnalysis::advancedSecurity
						),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningAiDetectionDriftGroup(
						desired,
						securityFlag(
								actual,
								SecurityAndAnalysis::secretScanningAiDetection
						),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningDelegatedAlertDismissalDriftGroup(
						desired,
						securityFlag(
								actual,
								SecurityAndAnalysis::secretScanningDelegatedAlertDismissal
						),
						client,
						org,
						actual.summary().name()
				)
		);
		groups.add(
				new SecretScanningDelegatedBypassDriftGroup(
						desired,
						securityFlag(
								actual,
								SecurityAndAnalysis::secretScanningDelegatedBypass
						),
						bypassReviewers(actual),
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

	/**
	 * What a fix run achieved for one repository: the items it resolved, and
	 * the ones it did not together with why.
	 */
	record FixOutcome(
			List<DriftItem> fixed,
			List<FixResult.Unfixed> unfixed
	) {

		FixOutcome {
			fixed = List.copyOf(fixed);
			unfixed = List.copyOf(unfixed);
		}

		List<DriftItem> unfixedItems() {
			return unfixed.stream().map(FixResult.Unfixed::item).toList();
		}

	}

	/**
	 * Runs every fix and accounts for the result per drift item.
	 * <p>
	 * Accounting is by item, not by rendered message. Messages are built for
	 * people and are not unique — before drift paths were namespaced, thirteen
	 * groups rendered the same {@code "enabled: want=true got=false"}, and
	 * subtracting them with {@code List.removeAll} deleted every equal line, so
	 * one successful fix erased twelve other settings' drift including failed
	 * ones. Working from the items themselves removes that whole class of bug
	 * rather than relying on the paths staying distinct.
	 */
	FixOutcome applyFixes(
			String name,
			Map<DriftGroup, List<DriftFix>> groupDrifts
	) {
		var fixed = new ArrayList<DriftItem>();
		var unfixed = new ArrayList<FixResult.Unfixed>();

		for (var fixes : groupDrifts.values()) {
			for (var driftFix : fixes) {
				if (driftFix.items().isEmpty()) {
					continue;
				}
				FixResult fixResult;
				try {
					fixResult = driftFix.fix().execute();
				} catch (RuntimeException e) {
					// The fix blew up, so nothing it covered got fixed.
					String reason = e.getMessage() == null
							? e.getClass().getSimpleName()
							: e.getMessage();
					driftFix.items()
							.forEach(
									item -> unfixed.add(
											new FixResult.Unfixed(item, reason)
									)
							);
					continue;
				}
				var unfixedByItem = fixResult.unfixedItems()
						.stream()
						.collect(
								Collectors.toMap(
										FixResult.Unfixed::item,
										u -> u,
										(a, _) -> a
								)
						);
				for (DriftItem item : driftFix.items()) {
					FixResult.Unfixed u = unfixedByItem.get(item);
					if (u == null) {
						fixed.add(item);
					} else {
						unfixed.add(u);
					}
				}
			}
		}
		return new FixOutcome(fixed, unfixed);
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
			case OK -> {
				System.out.printf("[OK]      %s%n", r.name());
				printFixReports(r);
			}
			case DRIFT -> {
				System.out.printf("[DRIFT]   %s:%n", r.name());
				if (r.fixReports().isEmpty()) {
					r.diffs()
							.forEach(
									d -> System.out
											.printf("            %s%n", d)
							);
				} else {
					printFixReports(r);
				}
				if (!r.fixPreview().isEmpty()) {
					System.out.printf(
							"  Would fix: %s%n",
							String.join(", ", r.fixPreview())
					);
				}
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
		System.out.printf("Missing:        %d%n", result.missingCount());

		List<String> failures = result.fixFailures();
		if (!failures.isEmpty()) {
			System.out.println();
			System.out.printf("=== Failed fixes (%d) ===%n", failures.size());
			failures.forEach(f -> System.out.printf("  %s%n", f));
		}
	}

	private static void printFixReports(CheckResult.RepoCheckResult r) {
		r.fixReports()
				.forEach(
						report -> System.out
								.printf("            %s%n", report.message())
				);
	}

}
