package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.ArrayList;
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
import io.github.arlol.githubcheck.drift.DriftFixer;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.drift.EnvironmentConfigDriftGroup;
import io.github.arlol.githubcheck.drift.EnvironmentSecretsDriftGroup;
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

/**
 * Checks the repositories of one owner: fetches each one's actual state,
 * compares it against {@code drifty.pkl}, and — in fix mode — writes the drift
 * away.
 */
public class RepositoryChecker {

	private final GitHubClient client;
	private final boolean fix;
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

	public RepositoryChecker(String token, boolean fix) {
		this(new GitHubClient(token), fix, Map.of(), new DriftyState());
	}

	public RepositoryChecker(
			String token,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this(new GitHubClient(token), fix, githubSecrets, state);
	}

	RepositoryChecker(GitHubClient client, boolean fix) {
		this(client, fix, Map.of(), new DriftyState());
	}

	RepositoryChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this(client, fix, githubSecrets, new DriftyState());
	}

	RepositoryChecker(
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

		List<CheckResult.Entry> results = new ArrayList<>();

		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<CheckResult.Entry>> futures = found.entrySet()
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
			for (Future<CheckResult.Entry> f : futures) {
				results.add(f.get());
			}
		}

		// Repos declared in config but not found under their owner
		desiredByRef.keySet()
				.stream()
				.filter(ref -> !found.containsKey(ref))
				.map(ref -> CheckResult.Entry.missing(ref.name()))
				.forEach(results::add);

		double fetchSeconds = (System.currentTimeMillis() - startFetch)
				/ 1000.0;
		System.out.printf("Fetch complete in %.2f seconds%n%n", fetchSeconds);

		return CheckResult.ofRepos(results);
	}

	private CheckResult.Entry checkOne(
			RepoRef ref,
			RepositorySummaryResponse summary,
			Drifty.Repository desired
	) {
		String name = ref.name();
		if (desired == null) {
			return CheckResult.Entry.unknown(name);
		}
		try {
			ManagedGroups<Drifty.GroupName> managed = ManagedGroups
					.of(desired.managed);
			List<String> unmanaged = managed.unmanaged()
					.stream()
					.map(Drifty.GroupName::toString)
					.toList();

			RepositoryState state = fetchState(ref, summary, managed);

			Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts = computeGroupDrifts(
					state,
					desired
			);

			if (fix) {
				DriftFixer.FixOutcome outcome = DriftFixer
						.applyFixes(groupDrifts);
				return CheckResult.Entry.fixed(
						name,
						DriftFixer.render(outcome.unfixedItems()),
						DriftFixer.fixReports(outcome)
				);
			}

			List<String> diffs = groupDrifts.values()
					.stream()
					.flatMap(List::stream)
					.flatMap(driftFix -> driftFix.items().stream())
					.map(DriftItem::message)
					.collect(Collectors.toCollection(ArrayList::new));

			if (diffs.isEmpty()) {
				return CheckResult.Entry.ok(name, unmanaged);
			}
			// In check mode, preview which groups --fix would act on.
			List<String> fixPreview = groupDrifts.keySet()
					.stream()
					.map(group -> group.name().toString())
					.toList();
			return CheckResult.Entry.drift(name, diffs, fixPreview, unmanaged);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return CheckResult.Entry.error(name, e.getMessage());
		} catch (IOException e) {
			return CheckResult.Entry.error(name, e.getMessage());
		}
	}

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	RepositoryState fetchState(
			RepoRef ref,
			RepositorySummaryResponse summary,
			ManagedGroups<Drifty.GroupName> managed
	) throws IOException, InterruptedException {
		String org = ref.owner();
		String name = ref.name();
		boolean archived = summary.archived();

		var details = client.getRepo(org, name);

		SecurityFlags security = archived ? SecurityFlags.NONE
				: fetchSecurityFlags(org, name, managed);

		Map<String, ActualBranchProtection> branchProtections = managed
				.manages(Drifty.GroupName.BRANCH_PROTECTION)
						? fetchBranchProtections(summary, org, name, archived)
						: Map.of();

		List<ActualSecret> secrets = managed
				.manages(Drifty.GroupName.ACTION_SECRETS)
						? secrets(client.getActionSecrets(org, name))
						: List.of();

		// One listing serves two groups, so it runs when either wants it; the
		// per-environment secret call only when environment_secrets does.
		Map<String, ActualEnvironment> environments = new LinkedHashMap<>();
		Map<String, List<ActualSecret>> envSecrets = new LinkedHashMap<>();
		boolean wantEnvConfig = managed
				.manages(Drifty.GroupName.ENVIRONMENT_CONFIG);
		boolean wantEnvSecrets = managed
				.manages(Drifty.GroupName.ENVIRONMENT_SECRETS);
		if (wantEnvConfig || wantEnvSecrets) {
			for (EnvironmentDetailsResponse env : client
					.getEnvironments(org, name)) {
				environments.put(env.name(), ActualTypes.environment(env));
				if (wantEnvSecrets) {
					envSecrets.put(
							env.name(),
							secrets(
									client.getEnvironmentSecrets(
											org,
											name,
											env.name()
									)
							)
					);
				}
			}
		}

		var workflowPermissions = managed
				.manages(Drifty.GroupName.WORKFLOW_PERMISSIONS)
						? ActualTypes.workflowPermissions(
								client.getWorkflowPermissions(org, name)
						)
						: null;

		List<ActualRuleset> rulesets = archived
				|| !managed.manages(Drifty.GroupName.RULESETS) ? List.of()
						: fetchRulesets(org, name);

		var pages = archived || !managed.manages(Drifty.GroupName.PAGES)
				? Optional.<PagesResponse>empty()
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

	/**
	 * Each flag is its own request, so each is guarded by its own group. Java's
	 * {@code &&} short-circuits, which is what keeps an unmanaged flag from
	 * sending one.
	 */
	private SecurityFlags fetchSecurityFlags(
			String org,
			String name,
			ManagedGroups<Drifty.GroupName> managed
	) {
		boolean vulnAlerts = managed
				.manages(Drifty.GroupName.VULNERABILITY_ALERTS)
				&& client.getVulnerabilityAlerts(org, name);
		boolean automatedSecurityFixes = managed
				.manages(Drifty.GroupName.AUTOMATED_SECURITY_FIXES)
				&& client.getAutomatedSecurityFixes(org, name);
		boolean immutableReleases = false;
		if (managed.manages(Drifty.GroupName.IMMUTABLE_RELEASES)) {
			var response = client.getImmutableReleases(org, name);
			immutableReleases = response.isPresent()
					&& response.orElseThrow().enabled();
		}
		boolean privateVulnerabilityReporting = managed
				.manages(Drifty.GroupName.PRIVATE_VULNERABILITY_REPORTING)
				&& client.getPrivateVulnerabilityReporting(org, name);
		boolean codeScanningDefaultSetup = managed
				.manages(Drifty.GroupName.CODE_SCANNING_DEFAULT_SETUP)
				&& client.getCodeScanningDefaultSetup(org, name);
		return new SecurityFlags(
				vulnAlerts,
				automatedSecurityFixes,
				immutableReleases,
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

	Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
		for (var group : createDriftGroups(actual, desired)) {
			var fixes = group.detect();
			if (!fixes.isEmpty()) {
				groupDrifts.put(group, fixes);
			}
		}
		return groupDrifts;
	}

	List<DriftGroup<Drifty.GroupName>> createDriftGroups(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		var ref = new RepoRef(desired.owner, actual.name());
		ActualSecurityAndAnalysis security = actual.securityAndAnalysis();
		ManagedGroups<Drifty.GroupName> managed = ManagedGroups
				.of(desired.managed);

		if (desired.archived) {
			// When archiving (or already archived): only check archived state,
			// skip all other groups since settings don't matter for archived
			// repos.
			return onlyManaged(
					List.of(
							new ArchivedDriftGroup(
									true,
									actual.repository().archived(),
									client,
									ref
							)
					),
					managed
			);
		}

		var groups = new ArrayList<DriftGroup<Drifty.GroupName>>();

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

		return onlyManaged(groups, managed);
	}

	/**
	 * Drops the groups this repository does not manage.
	 * <p>
	 * One filter over the finished list, rather than a check at each of the two
	 * dozen {@code groups.add} calls: a group added later is filtered without
	 * its author having to know this feature exists.
	 */
	private static List<DriftGroup<Drifty.GroupName>> onlyManaged(
			List<DriftGroup<Drifty.GroupName>> groups,
			ManagedGroups<Drifty.GroupName> managed
	) {
		return groups.stream().filter(g -> managed.manages(g.name())).toList();
	}

}
