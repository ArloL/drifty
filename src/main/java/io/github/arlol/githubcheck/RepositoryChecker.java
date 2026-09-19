package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.drift.ActionSecretsDriftGroup;
import io.github.arlol.githubcheck.drift.ActionVariablesDriftGroup;
import io.github.arlol.githubcheck.drift.AdvancedSecurityDriftGroup;
import io.github.arlol.githubcheck.drift.ArchivedDriftGroup;
import io.github.arlol.githubcheck.drift.AutomatedSecurityFixesDriftGroup;
import io.github.arlol.githubcheck.drift.BranchProtectionDriftGroup;
import io.github.arlol.githubcheck.drift.CodeScanningDefaultSetupDriftGroup;
import io.github.arlol.githubcheck.drift.CollaboratorsDriftGroup;
import io.github.arlol.githubcheck.drift.CustomPropertiesDriftGroup;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftReport;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.drift.EnvironmentConfigDriftGroup;
import io.github.arlol.githubcheck.drift.EnvironmentSecretsDriftGroup;
import io.github.arlol.githubcheck.drift.EnvironmentVariablesDriftGroup;
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
import io.github.arlol.githubcheck.drift.WebhooksDriftGroup;
import io.github.arlol.githubcheck.drift.WorkflowPermissionsDriftGroup;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Checks the repositories of one owner: compares each one's actual state
 * against {@code drifty.pkl} and — in fix mode — writes the drift away. Reading
 * that state is {@link RepositoryStateReader}'s job.
 */
public class RepositoryChecker {

	private final GitHubClient client;
	private final RepositoryStateReader reader;
	private final boolean fix;
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

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
		this.reader = new RepositoryStateReader(client, FetchFailures.STRICT);
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
	}

	/**
	 * Checks one account's repositories: everything GitHub lists under
	 * {@code owner}, compared against what the config nests under that account.
	 * <p>
	 * The owner arrives as a parameter because it is the key of the config
	 * block a repository sits in, not a field on the repository. Listing the
	 * repositories is the caller's job too — an organization and a personal
	 * account are listed through different endpoints.
	 */
	public List<CheckResult.Entry> check(
			String owner,
			List<RepositorySummaryResponse> summaries,
			List<Drifty.Repository> desired
	) throws InterruptedException, ExecutionException {
		return check(owner, () -> summaries, desired);
	}

	public List<CheckResult.Entry> check(
			String owner,
			Supplier<List<RepositorySummaryResponse>> listing,
			List<Drifty.Repository> desired
	) throws InterruptedException, ExecutionException {
		Map<String, Drifty.Repository> desiredByName = desired.stream()
				.collect(
						Collectors.toMap(
								r -> r.name,
								r -> r,
								(a, _) -> a,
								LinkedHashMap::new
						)
				);

		List<CheckResult.Entry> results = new ArrayList<>();
		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			// Everything the config declares and wants active starts now. The
			// listing used to be what said which repositories to check, and
			// waiting for it was 596ms of a traced 3.09s fetch with fewer than
			// ten requests in flight. Nothing a repository is read for needs
			// another repository, or the listing.
			Map<String, Future<CheckResult.Entry>> started = new LinkedHashMap<>();
			desiredByName.forEach((name, repo) -> {
				if (!repo.archived) {
					started.put(
							name,
							executor.submit(
									() -> checkOne(
											new RepoRef(owner, name),
											repo
									)
							)
					);
				}
			});

			// What only the listing can answer: what GitHub has that the
			// config does not declare, what it declares that GitHub does not
			// have, and whether a repository the config wants archived is.
			List<RepositorySummaryResponse> summaries = listing.get();
			Set<String> listed = summaries.stream()
					.map(RepositorySummaryResponse::name)
					.collect(Collectors.toSet());

			for (RepositorySummaryResponse summary : summaries) {
				Drifty.Repository repo = desiredByName.get(summary.name());
				if (repo == null) {
					results.add(CheckResult.Entry.unknown(summary.name()));
				} else if (repo.archived) {
					results.add(
							archivedEntry(
									new RepoRef(owner, summary.name()),
									repo,
									summary.archived()
							)
					);
				}
			}

			for (var entry : desiredByName.entrySet()) {
				String name = entry.getKey();
				if (!listed.contains(name)) {
					// Declared but not there. A repository already started
					// spends its requests on 404s before this is known; that
					// is the abnormal case, and the listing is still what
					// decides.
					results.add(CheckResult.Entry.missing(name));
				} else if (!entry.getValue().archived) {
					results.add(started.get(name).get());
				}
			}
		}

		return List.copyOf(results);
	}

	/**
	 * One repository the config declares and wants active, read and compared.
	 */
	private CheckResult.Entry checkOne(RepoRef ref, Drifty.Repository desired) {
		ManagedGroups<Drifty.GroupName> managed = ManagedGroups
				.of(desired.managed);
		return entry(ref.name(), managed, () -> {
			// `publicRepository` comes from the config rather than from
			// GitHub for the same reason `archived` does: the config is what
			// says which repositories to read, and reading the answer off
			// `GET /repos/{owner}/{repo}` would put the branch listing behind
			// it and each branch's protection behind that.
			RepositoryState state = reader.fetchState(
					ref,
					managed,
					false,
					Drifty.Visibility.PUBLIC == desired.visibility
			);
			return createDriftGroups(state, desired);
		});
	}

	/**
	 * A repository the config wants archived, compared on {@code archived}
	 * alone. The account listing carries that boolean, so there is nothing left
	 * to ask GitHub for — 51 archived repositories of one 101-repository
	 * account spent a request each on it.
	 */
	private CheckResult.Entry archivedEntry(
			RepoRef ref,
			Drifty.Repository desired,
			boolean archived
	) {
		ManagedGroups<Drifty.GroupName> managed = ManagedGroups
				.of(desired.managed);
		return entry(
				ref.name(),
				managed,
				() -> archivedOnlyGroups(ref, archived, managed)
		);
	}

	/**
	 * What the groups a repository produced make of it, in check mode or in fix
	 * mode.
	 * <p>
	 * The unmanaged list comes from the config's own block, not from whatever
	 * was read: what a repository declares unmanaged is what the report names,
	 * and reading less is not a declaration.
	 */
	private CheckResult.Entry entry(
			String name,
			ManagedGroups<Drifty.GroupName> managed,
			Groups groups
	) {
		try {
			return DriftReport.entry(name, managed, groups.get(), fix);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return CheckResult.Entry.error(name, e.getMessage());
		} catch (IOException e) {
			return CheckResult.Entry.error(name, e.getMessage());
		} catch (GitHubApiException e) {
			// GitHubClient turns every failed request into this, and it is a
			// RuntimeException: without this arm it escapes the virtual thread
			// this runs on, resurfaces at Future.get() wrapped in an
			// ExecutionException and ends the whole run, so one repository's
			// 403 costs the report for every repository after it.
			// OrganizationChecker.checkOne has the same arm.
			return CheckResult.Entry.error(name, e.getMessage());
		}
	}

	/** One repository's drift groups, which reading them can fail. */
	private interface Groups {

		List<DriftGroup<Drifty.GroupName>> get()
				throws IOException, InterruptedException;

	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	/**
	 * This repository's drifted groups — see {@link DriftReport#groupDrifts}.
	 */
	Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		return DriftReport.groupDrifts(createDriftGroups(actual, desired));
	}

	/**
	 * The only group a repository the config wants archived is compared on.
	 * <p>
	 * Takes {@code actualArchived} rather than a {@link RepositoryState}
	 * because that boolean is the whole of what the comparison needs, and
	 * {@code checkOne} has it off the account listing without sending anything.
	 * {@code createDriftGroups} reaches the same groups from a state it was
	 * handed, which is the shape the export needs — it renders an archived
	 * repository's settings and so reads them.
	 */
	private List<DriftGroup<Drifty.GroupName>> archivedOnlyGroups(
			RepoRef ref,
			boolean actualArchived,
			ManagedGroups<Drifty.GroupName> managed
	) {
		return onlyManaged(
				List.of(
						new ArchivedDriftGroup(
								true,
								actualArchived,
								client,
								ref
						)
				),
				managed
		);
	}

	List<DriftGroup<Drifty.GroupName>> createDriftGroups(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		var ref = actual.ref();
		ActualSecurityAndAnalysis security = actual.securityAndAnalysis();
		ManagedGroups<Drifty.GroupName> managed = ManagedGroups
				.of(desired.managed);

		if (desired.archived) {
			// When archiving (or already archived): only check archived state,
			// skip all other groups since settings don't matter for archived
			// repos.
			return archivedOnlyGroups(
					ref,
					actual.repository().archived(),
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
						desired.pages != null,
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

		// Variables
		groups.add(
				new ActionVariablesDriftGroup(
						desired.actionsVariables,
						actual.actionVariables(),
						client,
						ref
				)
		);
		groups.add(
				new EnvironmentVariablesDriftGroup(
						desired.environments,
						actual.environmentVariables(),
						client,
						ref
				)
		);

		// Webhooks
		groups.add(
				new WebhooksDriftGroup(
						desired.webhooks,
						actual.webhooks(),
						githubSecrets,
						state,
						client,
						ref
				)
		);

		// Custom properties
		groups.add(
				new CustomPropertiesDriftGroup(
						desired.customProperties,
						desired.customMultiSelectProperties,
						actual.customPropertyValues(),
						actual.repository().organizationOwned(),
						client,
						ref
				)
		);

		// Collaborators
		groups.add(
				new CollaboratorsDriftGroup(
						desired.collaborators,
						desired.teamPermissions,
						actual.collaborators(),
						actual.repository().organizationOwned(),
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
