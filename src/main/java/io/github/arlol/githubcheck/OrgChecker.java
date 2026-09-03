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
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.RulesetSourceType;
import io.github.arlol.githubcheck.client.Secret;
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
	private final boolean fix;
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

	public OrgChecker(String token, boolean fix) {
		this(new GitHubClient(token), fix, Map.of(), new DriftyState());
	}

	public OrgChecker(
			String token,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this(new GitHubClient(token), fix, githubSecrets, state);
	}

	OrgChecker(GitHubClient client, boolean fix) {
		this(client, fix, Map.of(), new DriftyState());
	}

	OrgChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this(client, fix, githubSecrets, new DriftyState());
	}

	OrgChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this.client = client;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
	}

	/**
	 * Checks every repository the config declares, under the owner the config
	 * declares it under.
	 * <p>
	 * The owner is a field on each {@code Repository} in {@code drifty.pkl}, as
	 * SPEC.md describes. It used to be ignored in favour of a hardcoded
	 * literal, which meant editing the config's owner had no effect and a
	 * second owner could not be reached at all.
	 */
	public CheckResult check(List<Drifty.Repository> repositories)
			throws IOException, InterruptedException, ExecutionException {
		// Repository names are only unique within an owner.
		Map<RepoRef, Drifty.Repository> desiredByRef = repositories.stream()
				.collect(
						Collectors.toMap(
								r -> new RepoRef(r.owner, r.name),
								r -> r,
								(a, _) -> a,
								LinkedHashMap::new
						)
				);

		List<String> owners = repositories.stream()
				.map(r -> r.owner)
				.distinct()
				.toList();

		long startFetch = System.currentTimeMillis();

		Map<RepoRef, RepositorySummaryResponse> found = new LinkedHashMap<>();
		for (String owner : owners) {
			System.out.println("Fetching repo list for owner: " + owner);
			for (RepositorySummaryResponse summary : client
					.listOrgRepos(owner)) {
				found.put(new RepoRef(owner, summary.name()), summary);
			}
		}
		System.out.printf(
				"Found %d repos. Fetching details in parallel...%n",
				found.size()
		);

		List<CheckResult.RepoCheckResult> results = new ArrayList<>();

		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<CheckResult.RepoCheckResult>> futures = found.entrySet()
					.stream()
					.map(
							entry -> executor
									.submit(
											() -> checkOne(
													entry.getKey(),
													entry.getValue(),
													desiredByRef
															.get(entry.getKey())
											)
									)
					)
					.toList();
			for (Future<CheckResult.RepoCheckResult> f : futures) {
				results.add(f.get());
			}
		}

		// Repos declared in config but not found under their owner
		desiredByRef.keySet()
				.stream()
				.filter(ref -> !found.containsKey(ref))
				.map(ref -> CheckResult.RepoCheckResult.missing(ref.name()))
				.forEach(results::add);

		double fetchSeconds = (System.currentTimeMillis() - startFetch)
				/ 1000.0;
		System.out.printf("Fetch complete in %.2f seconds%n%n", fetchSeconds);

		return new CheckResult(Collections.unmodifiableList(results));
	}

	private CheckResult.RepoCheckResult checkOne(
			RepoRef ref,
			RepositorySummaryResponse summary,
			Drifty.Repository desired
	) {
		String name = ref.name();
		if (desired == null) {
			return CheckResult.RepoCheckResult.unknown(name);
		}
		try {
			RepositoryState state = fetchState(ref, summary);

			Map<DriftGroup, List<DriftFix>> groupDrifts = computeGroupDrifts(
					state,
					desired
			);

			if (fix) {
				FixOutcome outcome = applyFixes(groupDrifts);
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

	RepositoryState fetchState(RepoRef ref, RepositorySummaryResponse summary)
			throws IOException, InterruptedException {
		String org = ref.owner();
		String name = ref.name();
		boolean archived = summary.archived();

		var details = client.getRepo(org, name);

		SecurityFlags security = archived ? SecurityFlags.NONE
				: fetchSecurityFlags(org, name);

		Map<String, ActualBranchProtection> branchProtections = fetchBranchProtections(
				summary,
				org,
				name,
				archived
		);

		List<ActualSecret> secrets = secrets(
				client.getActionSecrets(org, name)
		);

		Map<String, ActualEnvironment> environments = new LinkedHashMap<>();
		Map<String, List<ActualSecret>> envSecrets = new LinkedHashMap<>();
		for (EnvironmentDetailsResponse env : client
				.getEnvironments(org, name)) {
			environments.put(env.name(), ActualTypes.environment(env));
			envSecrets.put(
					env.name(),
					secrets(client.getEnvironmentSecrets(org, name, env.name()))
			);
		}

		var workflowPermissions = ActualTypes
				.workflowPermissions(client.getWorkflowPermissions(org, name));

		List<ActualRuleset> rulesets = archived ? List.of()
				: fetchRulesets(org, name);

		var pages = archived ? Optional.<PagesResponse>empty()
				: client.getPages(org, name);

		return new RepositoryState(
				name,
				ActualTypes.repository(details),
				ActualTypes.securityAndAnalysis(details),
				security.vulnAlerts(),
				security.automatedSecurityFixes(),
				security.immutableReleases(),
				security.privateVulnerabilityReporting(),
				security.codeScanningDefaultSetup(),
				branchProtections,
				rulesets,
				secrets,
				environments,
				envSecrets,
				workflowPermissions,
				pages.map(ActualTypes::pages)
		);
	}

	private static List<ActualSecret> secrets(List<Secret> responses) {
		return responses.stream().map(ActualTypes::secret).toList();
	}

	private SecurityFlags fetchSecurityFlags(String org, String name) {
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
				codeScanningDefaultSetup
		);
	}

	/**
	 * One request per protected branch, after the one that lists them. REST has
	 * no call that returns every protection at once, so this is the shape the
	 * read takes until GraphQL bulk reads land; when they do, this method and
	 * {@link #fetchRulesets} are the two places to replace, since everything
	 * downstream sees {@link ActualBranchProtection} only.
	 */
	private Map<String, ActualBranchProtection> fetchBranchProtections(
			RepositorySummaryResponse summary,
			String org,
			String name,
			boolean archived
	) {
		Map<String, ActualBranchProtection> branchProtections = new HashMap<>();
		if (archived || RepositoryVisibility.PUBLIC != summary.visibility()) {
			return branchProtections;
		}
		for (var branch : client.getBranches(org, name, true)) {
			var bp = client.getBranchProtection(org, name, branch.name());
			branchProtections.put(
					branch.name(),
					ActualTypes.branchProtection(bp.orElseThrow())
			);
		}
		return branchProtections;
	}

	/**
	 * One request per ruleset, after the one that lists them: the listing
	 * carries no rules or conditions, and REST has no bulk read for them. See
	 * {@link #fetchBranchProtections} for what replaces both.
	 */
	private List<ActualRuleset> fetchRulesets(String org, String name) {
		var rulesets = new ArrayList<ActualRuleset>();
		for (var rs : client.listRulesets(org, name)) {
			if (rs.sourceType() == RulesetSourceType.ORGANIZATION) {
				// listRulesets hits /rulesets, whose includes_parents defaults
				// to true, so org rulesets arrive here. They are not the
				// repository's to reconcile: the repo endpoint cannot delete
				// one, so reporting it as extra produces a fix that always
				// fails.
				continue;
			}
			rulesets.add(
					ActualTypes.ruleset(client.getRuleset(org, name, rs.id()))
			);
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
			boolean codeScanningDefaultSetup
	) {

		private static final SecurityFlags NONE = new SecurityFlags(
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

	List<DriftGroup> createDriftGroups(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		var ref = new RepoRef(desired.owner, actual.name());
		ActualSecurityAndAnalysis security = actual.securityAndAnalysis();

		if (desired.archived) {
			// When archiving (or already archived): only check archived state,
			// skip all other groups since settings don't matter for archived
			// repos.
			return List.of(
					new ArchivedDriftGroup(
							true,
							actual.repository().archived(),
							client,
							ref
					)
			);
		}

		var groups = new ArrayList<DriftGroup>();

		// When actual.archived=false this detects nothing and
		// computeGroupDrifts skips it. When it does drift, applyFixes runs it
		// before the rest — see DriftGroup.runsBeforeOtherFixes(); its
		// position in this list is not what guarantees that.
		groups.add(
				new ArchivedDriftGroup(
						false,
						actual.repository().archived(),
						client,
						ref
				)
		);

		groups.add(
				new RepoSettingsDriftGroup(
						desired,
						actual.repository(),
						client,
						ref
				)
		);
		groups.add(
				new TopicsDriftGroup(
						desired.topics,
						actual.repository().topics(),
						client,
						ref
				)
		);
		groups.add(
				new WorkflowPermissionsDriftGroup(
						desired.defaultWorkflowPermissions,
						desired.canApprovePullRequestReviews,
						actual.workflowPermissions(),
						client,
						ref
				)
		);
		groups.add(
				new PagesDriftGroup(desired.pages, actual.pages(), client, ref)
		);

		// Environment config
		groups.add(
				new EnvironmentConfigDriftGroup(
						desired.environments,
						actual.environments(),
						client,
						ref
				)
		);

		// Secrets
		groups.add(
				new ActionSecretsDriftGroup(
						desired.actionsSecrets,
						actual.actionSecrets(),
						githubSecrets,
						state,
						client,
						ref
				)
		);
		groups.add(
				new EnvironmentSecretsDriftGroup(
						desired.environments,
						actual.environmentSecrets(),
						githubSecrets,
						state,
						client,
						ref
				)
		);

		// Security micro-groups
		groups.add(
				new VulnerabilityAlertsDriftGroup(
						desired.vulnerabilityAlerts,
						actual.vulnerabilityAlerts(),
						client,
						ref
				)
		);
		groups.add(
				new AutomatedSecurityFixesDriftGroup(
						desired.automatedSecurityFixes,
						actual.automatedSecurityFixes(),
						client,
						ref
				)
		);
		groups.add(
				new ImmutableReleasesDriftGroup(
						desired.immutableReleases,
						actual.immutableReleases(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningDriftGroup(
						desired.secretScanning,
						security.secretScanning(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningPushProtectionDriftGroup(
						desired.secretScanningPushProtection,
						security.secretScanningPushProtection(),
						client,
						ref
				)
		);
		groups.add(
				new PrivateVulnerabilityReportingDriftGroup(
						desired.privateVulnerabilityReporting,
						actual.privateVulnerabilityReporting(),
						client,
						ref
				)
		);
		groups.add(
				new CodeScanningDefaultSetupDriftGroup(
						desired.codeScanningDefaultSetup,
						actual.codeScanningDefaultSetup(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningNonProviderPatternsDriftGroup(
						desired.secretScanningNonProviderPatterns,
						security.secretScanningNonProviderPatterns(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningValidityChecksDriftGroup(
						desired.secretScanningValidityChecks,
						security.secretScanningValidityChecks(),
						client,
						ref
				)
		);
		groups.add(
				new AdvancedSecurityDriftGroup(
						desired.advancedSecurity,
						security.advancedSecurity(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningAiDetectionDriftGroup(
						desired.secretScanningAiDetection,
						security.secretScanningAiDetection(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningDelegatedAlertDismissalDriftGroup(
						desired.secretScanningDelegatedAlertDismissal,
						security.secretScanningDelegatedAlertDismissal(),
						client,
						ref
				)
		);
		groups.add(
				new SecretScanningDelegatedBypassDriftGroup(
						desired.secretScanningDelegatedBypass,
						desired.secretScanningDelegatedBypassReviewers,
						security.secretScanningDelegatedBypass(),
						security.bypassReviewers(),
						client,
						ref
				)
		);

		// Branch protection
		groups.add(
				new BranchProtectionDriftGroup(
						desired.branchProtections,
						actual.branchProtections(),
						client,
						ref
				)
		);

		// Rulesets
		groups.add(
				new RulesetDriftGroup(
						desired.rulesets,
						actual.rulesets(),
						client,
						ref
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
	FixOutcome applyFixes(Map<DriftGroup, List<DriftFix>> groupDrifts) {
		var fixed = new ArrayList<DriftItem>();
		var unfixed = new ArrayList<FixResult.Unfixed>();

		for (DriftFix driftFix : prerequisitesFirst(groupDrifts)) {
			if (!driftFix.items().isEmpty()) {
				apply(driftFix, fixed, unfixed);
			}
		}
		return new FixOutcome(fixed, unfixed);
	}

	/**
	 * Every fix to run, with the groups that declare themselves prerequisites
	 * ahead of the rest — unarchiving, today, because GitHub rejects writes to
	 * an archived repository and every other fix would fail.
	 */
	private static List<DriftFix> prerequisitesFirst(
			Map<DriftGroup, List<DriftFix>> groupDrifts
	) {
		var ordered = new ArrayList<DriftFix>();
		groupDrifts.entrySet()
				.stream()
				.filter(e -> e.getKey().runsBeforeOtherFixes())
				.forEach(e -> ordered.addAll(e.getValue()));
		groupDrifts.entrySet()
				.stream()
				.filter(e -> !e.getKey().runsBeforeOtherFixes())
				.forEach(e -> ordered.addAll(e.getValue()));
		return ordered;
	}

	/** Runs one fix and records each of its items as fixed or not. */
	private static void apply(
			DriftFix driftFix,
			List<DriftItem> fixed,
			List<FixResult.Unfixed> unfixed
	) {
		Map<DriftItem, FixResult.Unfixed> unfixedByItem;
		try {
			unfixedByItem = byItem(driftFix.fix().execute());
		} catch (RuntimeException e) {
			// The fix blew up, so nothing it covered got fixed.
			unfixed.addAll(allUnfixed(driftFix, reason(e)));
			return;
		}
		for (DriftItem item : driftFix.items()) {
			FixResult.Unfixed u = unfixedByItem.get(item);
			if (u == null) {
				fixed.add(item);
			} else {
				unfixed.add(u);
			}
		}
	}

	private static Map<DriftItem, FixResult.Unfixed> byItem(FixResult result) {
		return result.unfixedItems()
				.stream()
				.collect(
						Collectors.toMap(
								FixResult.Unfixed::item,
								u -> u,
								(a, _) -> a
						)
				);
	}

	private static List<FixResult.Unfixed> allUnfixed(
			DriftFix driftFix,
			String reason
	) {
		return driftFix.items()
				.stream()
				.map(item -> new FixResult.Unfixed(item, reason))
				.toList();
	}

	private static String reason(RuntimeException e) {
		return e.getMessage() == null ? e.getClass().getSimpleName()
				: e.getMessage();
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
