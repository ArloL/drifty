package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.CollaboratorResponse;
import io.github.arlol.githubcheck.client.DeploymentBranchPolicyResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.ImmutableReleasesResponse;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepoTeamResponse;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.RulesetSourceType;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.client.VariableResponse;
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
import io.github.arlol.githubcheck.drift.DriftFixer;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
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
 * Checks the repositories of one owner: fetches each one's actual state,
 * compares it against {@code drifty.pkl}, and — in fix mode — writes the drift
 * away.
 */
public class RepositoryChecker {

	/**
	 * All the state a repository nobody will compare needs.
	 * <p>
	 * {@code RepositoryExporter.entry} renders no group's section once a
	 * repository <em>is</em> archived, so {@code ExportRunner} narrows what it
	 * asks {@code fetchState} for to this: every other group's requests were
	 * sent and the answers dropped, 49 archived repositories of one
	 * 101-repository account spending 343 of its 1152 requests that way on
	 * endpoints GitHub rejects every write to. {@code collectMissingSecrets}
	 * narrows the same way, so {@code --fix} is not aborted over a secret value
	 * nothing will write.
	 * <p>
	 * The check side no longer narrows — it fetches nothing. A repository the
	 * config wants archived is compared on {@code archived} alone and
	 * {@code checkOne} has that off the account listing, so there is no request
	 * left to narrow. The export cannot do the same: it writes the repository's
	 * settings down whether or not it compares them, and the listing does not
	 * carry the merge fields.
	 * <p>
	 * {@code fetchState} guards nothing on {@code ARCHIVED} itself: the group
	 * compares {@code archived} off the repository details request, which is
	 * sent whatever this says.
	 */
	static final Set<Drifty.GroupName> ARCHIVED_ONLY = Set
			.of(Drifty.GroupName.ARCHIVED);

	private final GitHubClient client;
	private final boolean fix;
	private final Map<String, String> githubSecrets;
	private final DriftyState state;
	private final FetchFailures failures;

	public RepositoryChecker(String token, boolean fix) {
		this(new GitHubClient(token), fix, Map.of(), new DriftyState());
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
		this(client, fix, githubSecrets, state, FetchFailures.STRICT);
	}

	RepositoryChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state,
			FetchFailures failures
	) {
		this.client = client;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
		this.failures = failures;
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
			RepositoryState state = fetchState(
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
		List<String> unmanaged = managed.unmanaged()
				.stream()
				.map(Drifty.GroupName::toString)
				.toList();
		try {
			Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts = computeGroupDrifts(
					groups.get()
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
			return CheckResult.Entry.drift(
					name,
					diffs,
					DriftFixer.fixPreview(groupDrifts),
					unmanaged
			);
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

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	/**
	 * One repository's actual state, with every request that can be in flight
	 * at once in flight at once.
	 * <p>
	 * The fan-out is two levels deep and never deeper. Everything that needs
	 * nothing starts immediately; only five reads wait, and each waits on the
	 * one response that names what it asks for — a branch's protection and a
	 * ruleset's rules on the listing they were named in, an environment's
	 * policies, secrets and variables on the environment listing, and the
	 * repository's teams and custom property values on {@code GET
	 * /repos/{owner}/{repo}}, which is what says whether an organization owns
	 * it. Sending them one after another instead made the deepest single
	 * repository the floor on the whole run: one repository of 101 took 24
	 * requests and 6.6s of the 9.5s a check of that account spent, while the
	 * semaphore in {@link GitHubClient} sat far below its limit throughout.
	 * <p>
	 * Two consequences of running them together, both deliberate.
	 * {@link FetchFailures#STRICT} no longer stops the reads after it — they
	 * are already sent — so a repository whose read fails now costs the
	 * requests the sequential version would have skipped, and when several fail
	 * the one that is reported is the first in join order rather than the first
	 * in request order. Join order is fixed, so the entry is still the same on
	 * every run; it is only no longer the same one the sequential version
	 * named. And {@link FetchFailures.Collecting} is written to from several
	 * threads, which is why it synchronizes and sorts.
	 */
	RepositoryState fetchState(
			RepoRef ref,
			RepositorySummaryResponse summary,
			ManagedGroups<Drifty.GroupName> managed
	) throws IOException, InterruptedException {
		return fetchState(
				ref,
				managed,
				summary.archived(),
				RepositoryVisibility.PUBLIC == summary.visibility()
		);
	}

	RepositoryState fetchState(
			RepoRef ref,
			ManagedGroups<Drifty.GroupName> managed,
			boolean archived,
			boolean publicRepository
	) throws IOException, InterruptedException {
		String org = ref.owner();
		String name = ref.name();

		try (var fanout = new Fanout<>(failures, managed)) {
			Supplier<RepositoryDetailsResponse> details = fanout
					.start(() -> client.getRepo(org, name));

			Supplier<SecurityFlags> security = archived
					? () -> SecurityFlags.NONE
					: fetchSecurityFlags(fanout, org, name);

			Supplier<Map<String, ActualBranchProtection>> branchProtections = fanout
					.read(
							Drifty.GroupName.BRANCH_PROTECTION,
							() -> fetchBranchProtections(
									fanout,
									publicRepository,
									org,
									name,
									archived
							),
							Map.of()
					);

			Supplier<List<ActualSecret>> secrets = fanout.read(
					Drifty.GroupName.ACTION_SECRETS,
					() -> secrets(client.getActionSecrets(org, name)),
					List.of()
			);

			Supplier<List<ActualVariable>> variables = fanout.read(
					Drifty.GroupName.ACTION_VARIABLES,
					() -> variables(client.getActionVariables(org, name)),
					List.of()
			);

			Supplier<Environments> environments = fetchEnvironments(
					fanout,
					org,
					name,
					managed
			);

			Supplier<ActualWorkflowPermissions> workflowPermissions = fanout
					.read(
							Drifty.GroupName.WORKFLOW_PERMISSIONS,
							() -> ActualTypes.workflowPermissions(
									client.getWorkflowPermissions(org, name)
							),
							null
					);

			Supplier<List<ActualRuleset>> rulesets = archived ? List::of
					: fanout.read(
							Drifty.GroupName.RULESETS,
							() -> fetchRulesets(fanout, org, name),
							List.of()
					);

			// has_pages answers what GET .../pages answers with a 404,
			// without spending the round trip on it: 37 of one account's 45
			// active repositories had no site. It is read off the details
			// response rather than off the account listing, which is what
			// keeps the listing off the critical path — the wait costs
			// nothing, because this is the last level of the fan-out and has
			// nothing below it.
			Supplier<Optional<PagesResponse>> pages = archived ? Optional::empty
					: fanout.read(
							Drifty.GroupName.PAGES,
							() -> details.get().hasPages()
									? client.getPages(org, name)
									: Optional.empty(),
							Optional.empty()
					);

			Supplier<List<ActualWebhook>> webhooks = fanout.read(
					Drifty.GroupName.WEBHOOKS,
					() -> client.getRepoWebhooks(org, name)
							.stream()
							.map(ActualTypes::webhook)
							.toList(),
					List.of()
			);

			Supplier<List<ActualCustomPropertyValue>> customPropertyValues = fetchCustomPropertyValues(
					fanout,
					org,
					name,
					managed,
					details
			);

			// Teams only exist under an organization; that endpoint 404s on a
			// personal account's repository too. The two reads are one group,
			// so they are started apart and joined inside one
			// FetchFailures.read: either failing leaves collaborators null and
			// records one failure, the way the single sequential read did.
			boolean wantCollaborators = managed
					.manages(Drifty.GroupName.COLLABORATORS);
			Supplier<List<CollaboratorResponse>> collaboratorList = wantCollaborators
					? fanout.start(() -> client.getCollaborators(org, name))
					: List::of;
			Supplier<List<RepoTeamResponse>> teams = wantCollaborators
					? fanout.start(
							() -> organizationOwned(details)
									? client.getRepoTeams(org, name)
									: List.of()
					) : List::of;

			Supplier<ActualCollaborators> collaborators = wantCollaborators
					? () -> failures.read(
							Drifty.GroupName.COLLABORATORS,
							() -> ActualTypes.collaborators(
									collaboratorList.get(),
									teams.get(),
									org
							),
							null
					)
					: () -> null;

			// Joined first, and outside every FetchFailures.read: a repository
			// whose own details cannot be read has no state to compare, and
			// the reads that wait on it would otherwise report its failure
			// under their own group's name. Everything else is joined below,
			// in the order the state's own fields are declared.
			RepositoryDetailsResponse repoDetails = details.get();
			SecurityFlags flags = security.get();
			Environments envs = environments.get();

			return new RepositoryState(
					ref,
					ActualTypes.repository(repoDetails),
					ActualTypes.securityAndAnalysis(repoDetails),
					flags.vulnAlerts(),
					ActualTypes.dependabotSecurityUpdates(repoDetails),
					flags.immutableReleases(),
					flags.privateVulnerabilityReporting(),
					flags.codeScanningDefaultSetup(),
					branchProtections.get(),
					rulesets.get(),
					secrets.get(),
					envs.environments(),
					envs.secrets(),
					workflowPermissions.get(),
					pages.get().map(ActualTypes::pages),
					variables.get(),
					envs.variables(),
					webhooks.get(),
					customPropertyValues.get(),
					collaborators.get()
			);
		}
	}

	/**
	 * Custom properties are an organization's schema; the values endpoint 404s
	 * on a personal account's repository, and no token scope changes that.
	 * Which is why this is the one group read that waits on the repository's
	 * own details: the owner's type is what says whether to ask at all, and
	 * asking it outside {@link FetchFailures#read} is what keeps a failed
	 * details request from being reported as this group's.
	 */
	private Supplier<List<ActualCustomPropertyValue>> fetchCustomPropertyValues(
			Fanout<Drifty.GroupName> fanout,
			String org,
			String name,
			ManagedGroups<Drifty.GroupName> managed,
			Supplier<RepositoryDetailsResponse> details
	) {
		if (!managed.manages(Drifty.GroupName.CUSTOM_PROPERTIES)) {
			return List::of;
		}
		return fanout.start(() -> {
			if (!organizationOwned(details)) {
				return List.of();
			}
			return failures.read(
					Drifty.GroupName.CUSTOM_PROPERTIES,
					() -> client.getRepoCustomPropertyValues(org, name)
							.stream()
							.map(ActualTypes::customPropertyValue)
							.toList(),
					List.of()
			);
		});
	}

	/**
	 * Whether an organization owns the repository, which two of its endpoints
	 * exist only under. Reads it back off {@code details} rather than off the
	 * listing summary so the answer comes from the one response every other
	 * repository field is read from.
	 */
	private static boolean organizationOwned(
			Supplier<RepositoryDetailsResponse> details
	) {
		return ActualTypes.repository(details.get()).organizationOwned();
	}

	private static List<ActualVariable> variables(
			List<VariableResponse> responses
	) {
		return responses.stream().map(ActualTypes::variable).toList();
	}

	/**
	 * The groups {@link #fetchState} could not read, for the exporter to note.
	 */
	List<FetchFailures.Failure> fetchFailures() {
		return failures.failures();
	}

	/**
	 * The three groups that share the environment listing, read together.
	 * <p>
	 * The listing is one request all three need, but each per-environment read
	 * below it is its own request and fails independently of the listing and of
	 * the others — wrapping the whole block under one group name would blame a
	 * secrets-only 403 on {@code environment_config}, and would throw away an
	 * already-fetched listing when only the variables read failed.
	 */
	private Supplier<Environments> fetchEnvironments(
			Fanout<Drifty.GroupName> fanout,
			String org,
			String name,
			ManagedGroups<Drifty.GroupName> managed
	) {
		boolean wantEnvConfig = managed
				.manages(Drifty.GroupName.ENVIRONMENT_CONFIG);
		boolean wantEnvSecrets = managed
				.manages(Drifty.GroupName.ENVIRONMENT_SECRETS);
		boolean wantEnvVariables = managed
				.manages(Drifty.GroupName.ENVIRONMENT_VARIABLES);
		if (!wantEnvConfig && !wantEnvSecrets && !wantEnvVariables) {
			return () -> Environments.NONE;
		}
		return fanout.start(() -> {
			List<PendingEnvironment> pending = failures
					.read(
							Drifty.GroupName.ENVIRONMENT_CONFIG,
							() -> client.getEnvironments(org, name),
							List.<EnvironmentDetailsResponse>of()
					)
					.stream()
					.map(
							env -> new PendingEnvironment(
									env,
									fanout.start(
											() -> failures.read(
													Drifty.GroupName.ENVIRONMENT_CONFIG,
													() -> branchPolicies(
															org,
															name,
															env,
															wantEnvConfig
													),
													List.of()
											)
									),
									wantEnvSecrets ? fanout.start(
											() -> failures.read(
													Drifty.GroupName.ENVIRONMENT_SECRETS,
													() -> secrets(
															client.getEnvironmentSecrets(
																	org,
																	name,
																	env.name()
															)
													),
													List.of()
											)
									) : null,
									wantEnvVariables ? fanout.start(
											() -> failures.read(
													Drifty.GroupName.ENVIRONMENT_VARIABLES,
													() -> variables(
															client.getEnvironmentVariables(
																	org,
																	name,
																	env.name()
															)
													),
													List.of()
											)
									) : null
							)
					)
					.toList();

			Map<String, ActualEnvironment> environments = new LinkedHashMap<>();
			Map<String, List<ActualSecret>> envSecrets = new LinkedHashMap<>();
			Map<String, List<ActualVariable>> envVariables = new LinkedHashMap<>();
			for (PendingEnvironment env : pending) {
				String envName = env.env().name();
				environments.put(
						envName,
						ActualTypes.environment(env.env(), env.policies().get())
				);
				if (env.secrets() != null) {
					envSecrets.put(envName, env.secrets().get());
				}
				if (env.variables() != null) {
					envVariables.put(envName, env.variables().get());
				}
			}
			return new Environments(environments, envSecrets, envVariables);
		});
	}

	/**
	 * One environment's three reads, already sent, waiting to be collected.
	 * {@code secrets} and {@code variables} are null when their group is
	 * unmanaged — no request was sent for them.
	 */
	private record PendingEnvironment(
			EnvironmentDetailsResponse env,
			Supplier<List<DeploymentBranchPolicyResponse>> policies,
			Supplier<List<ActualSecret>> secrets,
			Supplier<List<ActualVariable>> variables
	) {
	}

	/** What the environment listing and the reads under it produced. */
	private record Environments(
			Map<String, ActualEnvironment> environments,
			Map<String, List<ActualSecret>> secrets,
			Map<String, List<ActualVariable>> variables
	) {

		private static final Environments NONE = new Environments(
				Map.of(),
				Map.of(),
				Map.of()
		);

	}

	/**
	 * The custom branch policies of one environment: one request per
	 * environment that has them on, since the endpoint 404s otherwise, and only
	 * for {@code environment_config}, the group that compares them.
	 */
	private List<DeploymentBranchPolicyResponse> branchPolicies(
			String org,
			String name,
			EnvironmentDetailsResponse env,
			boolean wantEnvConfig
	) {
		var policy = env.deploymentBranchPolicy();
		if (!wantEnvConfig || policy == null
				|| !policy.customBranchPolicies()) {
			return List.of();
		}
		return client.getDeploymentBranchPolicies(org, name, env.name());
	}

	private static List<ActualSecret> secrets(List<Secret> responses) {
		return responses.stream().map(ActualTypes::secret).toList();
	}

	/**
	 * Five independent flags, five requests, all in flight together. Each is
	 * its own group because each is its own request, and an unmanaged one sends
	 * nothing.
	 */
	private Supplier<SecurityFlags> fetchSecurityFlags(
			Fanout<Drifty.GroupName> fanout,
			String org,
			String name
	) {
		Supplier<Boolean> vulnAlerts = fanout.read(
				Drifty.GroupName.VULNERABILITY_ALERTS,
				() -> client.getVulnerabilityAlerts(org, name),
				false
		);
		Supplier<Optional<ImmutableReleasesResponse>> immutableReleases = fanout
				.read(
						Drifty.GroupName.IMMUTABLE_RELEASES,
						() -> client.getImmutableReleases(org, name),
						Optional.empty()
				);
		Supplier<Boolean> privateVulnerabilityReporting = fanout.read(
				Drifty.GroupName.PRIVATE_VULNERABILITY_REPORTING,
				() -> client.getPrivateVulnerabilityReporting(org, name),
				false
		);
		Supplier<Boolean> codeScanningDefaultSetup = fanout.read(
				Drifty.GroupName.CODE_SCANNING_DEFAULT_SETUP,
				() -> client.getCodeScanningDefaultSetup(org, name),
				false
		);
		return () -> new SecurityFlags(
				vulnAlerts.get(),
				immutableReleases.get()
						.filter(ImmutableReleasesResponse::enabled)
						.isPresent(),
				privateVulnerabilityReporting.get(),
				codeScanningDefaultSetup.get()
		);
	}

	/**
	 * One request per protected branch, all sent as soon as the one that lists
	 * them answers. REST has no call that returns every protection at once, so
	 * this is the shape the read takes until GraphQL bulk reads land; when they
	 * do, this method and {@link #fetchRulesets} are the two places to replace,
	 * since everything downstream sees {@link ActualBranchProtection} only.
	 */
	private Map<String, ActualBranchProtection> fetchBranchProtections(
			Fanout<Drifty.GroupName> fanout,
			boolean publicRepository,
			String org,
			String name,
			boolean archived
	) {
		if (archived || !publicRepository) {
			return Map.of();
		}
		var pending = client.getBranches(org, name, true)
				.stream()
				.map(
						branch -> Map.entry(
								branch.name(),
								fanout.start(
										() -> client.getBranchProtection(
												org,
												name,
												branch.name()
										)
								)
						)
				)
				.toList();
		Map<String, ActualBranchProtection> branchProtections = new LinkedHashMap<>();
		for (var branch : pending) {
			branchProtections.put(
					branch.getKey(),
					ActualTypes.branchProtection(
							branch.getValue().get().orElseThrow()
					)
			);
		}
		return branchProtections;
	}

	/**
	 * One request per ruleset, all sent as soon as the one that lists them
	 * answers: the listing carries no rules or conditions, and REST has no bulk
	 * read for them. See {@link #fetchBranchProtections} for what replaces
	 * both.
	 */
	private List<ActualRuleset> fetchRulesets(
			Fanout<Drifty.GroupName> fanout,
			String org,
			String name
	) {
		return client.listRulesets(org, name)
				.stream()
				// listRulesets hits /rulesets, whose includes_parents defaults
				// to true, so org and enterprise rulesets arrive here. They
				// are not the repository's to reconcile: the repo endpoint
				// cannot delete one, so reporting it as extra produces a fix
				// that always fails.
				.filter(
						rs -> rs.sourceType() != RulesetSourceType.ORGANIZATION
								&& rs.sourceType() != RulesetSourceType.ENTERPRISE
				)
				.map(
						rs -> fanout.start(
								() -> client.getRuleset(org, name, rs.id())
						)
				)
				.toList()
				.stream()
				.map(Supplier::get)
				.map(ActualTypes::ruleset)
				.toList();
	}

	/**
	 * The security- and analysis-related repository flags GitHub serves from
	 * their own endpoints, all {@code false} for an archived repository since
	 * GitHub does not expose them there.
	 * <p>
	 * Automated security fixes are not among them: GitHub answers the same bit
	 * as {@code security_and_analysis.dependabot_security_updates} on the
	 * repository's own details, which is read for eight other security groups
	 * anyway, so asking {@code /automated-security-fixes} as well cost one
	 * request per repository for a value already in hand. The endpoint's
	 * {@code paused} field is the only thing it carries that the details do
	 * not, and nothing compares it. Unlike the four flags above, it is not
	 * forced false for an archived repository — {@code repoDetails} is read
	 * regardless, and its value is whatever GitHub reports.
	 */
	private record SecurityFlags(
			boolean vulnAlerts,
			boolean immutableReleases,
			boolean privateVulnerabilityReporting,
			boolean codeScanningDefaultSetup
	) {

		private static final SecurityFlags NONE = new SecurityFlags(
				false,
				false,
				false,
				false
		);

	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	/**
	 * The groups that drifted, with their fixes.
	 * <p>
	 * A group is in only when it reported a drifted item. Most groups return a
	 * {@link DriftFix} whether or not anything drifted — its item list is what
	 * says — so keying on "returned a fix" put twenty of the twenty-seven
	 * groups in the map on every run. {@code --fix} was unaffected, since
	 * {@link DriftFixer#applyFixes} skips an item-less fix, but the keys are
	 * also the {@code Would fix:} preview, and that named groups the operator's
	 * repository had no drift in.
	 */
	Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		return computeGroupDrifts(createDriftGroups(actual, desired));
	}

	private Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> computeGroupDrifts(
			List<DriftGroup<Drifty.GroupName>> groups
	) {
		Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
		for (var group : groups) {
			var fixes = group.detect();
			if (fixes.stream().anyMatch(fix -> !fix.items().isEmpty())) {
				groupDrifts.put(group, fixes);
			}
		}
		return groupDrifts;
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
