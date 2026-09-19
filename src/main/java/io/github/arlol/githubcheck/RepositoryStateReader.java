package io.github.arlol.githubcheck;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.CollaboratorResponse;
import io.github.arlol.githubcheck.client.DeploymentBranchPolicyResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.GraphQlRepositoryResponse;
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
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Reads one repository's actual state from GitHub.
 * <p>
 * Split from {@link RepositoryChecker} because reading and comparing have
 * different consumers: a check does both, an export only reads. Building a
 * whole checker to reach this half meant passing three arguments chosen to
 * neutralise the other one — {@code fix} false, no secrets, a throwaway state —
 * and left the export unable to say, in a type, that reading was all it wanted.
 * <p>
 * {@link FetchFailures} is the reader's own choice, not the checker's: it is
 * what decides whether a group's 403 ends the entry
 * ({@link FetchFailures#STRICT}, for a check) or becomes a note in the exported
 * file ({@link FetchFailures#collecting()}).
 */
public final class RepositoryStateReader {

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

	/** The groups one GraphQL query answers — see {@code fetchState}. */
	private static final Set<Drifty.GroupName> GRAPHQL_GROUPS = Set.of(
			Drifty.GroupName.RULESETS,
			Drifty.GroupName.BRANCH_PROTECTION,
			Drifty.GroupName.COLLABORATORS,
			Drifty.GroupName.VULNERABILITY_ALERTS
	);

	private final GitHubClient client;
	private final FetchFailures failures;

	public RepositoryStateReader(GitHubClient client, FetchFailures failures) {
		this.client = client;
		this.failures = failures;
	}

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

			// One query for four groups: the rulesets and their rules, the
			// branch protections, the collaborators and the vulnerability
			// alerts flag — six requests and both of the repository's
			// two-level chains, in one round trip. Each group still fails on
			// its own, because each is its own aliased selection.
			//
			// Not sent at all when none of the four is managed, the way each
			// group's own request was not: an account someone else
			// administers is where these answer 403.
			Supplier<GraphQlRepositoryResponse> graph = managed.managesAny(
					GRAPHQL_GROUPS
			) ? fanout.start(() -> client.graphqlRepository(org, name))
					: () -> {
						throw new GitHubApiException(
								"No GraphQL query was sent for " + org + "/"
										+ name
						);
					};

			Supplier<SecurityFlags> security = archived
					? () -> SecurityFlags.NONE
					: fetchSecurityFlags(fanout, graph, org, name);

			Supplier<Map<String, ActualBranchProtection>> branchProtections = fanout
					.read(
							Drifty.GroupName.BRANCH_PROTECTION,
							() -> branchProtections(
									graph,
									publicRepository,
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
							() -> rulesets(graph),
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
					? () -> graph.get().collaborators()
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
	/**
	 * Vulnerability alerts come off the GraphQL query; the other three are
	 * their own endpoints, none of which GraphQL exposes.
	 */
	private Supplier<SecurityFlags> fetchSecurityFlags(
			Fanout<Drifty.GroupName> fanout,
			Supplier<GraphQlRepositoryResponse> graph,
			String org,
			String name
	) {
		Supplier<Boolean> vulnAlerts = fanout.read(
				Drifty.GroupName.VULNERABILITY_ALERTS,
				() -> graph.get().vulnerabilityAlerts(),
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
	 * The protections the GraphQL query answered, keyed by branch.
	 * <p>
	 * Branch protection needs a paid plan on a private repository, and an
	 * archived one takes no writes, so neither is compared — and the query
	 * carries the answer either way, so this costs nothing to skip.
	 */
	private static Map<String, ActualBranchProtection> branchProtections(
			Supplier<GraphQlRepositoryResponse> graph,
			boolean publicRepository,
			boolean archived
	) {
		if (archived || !publicRepository) {
			return Map.of();
		}
		Map<String, ActualBranchProtection> out = new LinkedHashMap<>();
		graph.get()
				.branchProtections()
				.forEach(
						(branch, protection) -> out.put(
								branch,
								ActualTypes.branchProtection(protection)
						)
				);
		return out;
	}

	/**
	 * The rulesets the GraphQL query answered.
	 * <p>
	 * The query asks for the repository's own, but an organization's can still
	 * arrive — {@code source_type} is what says so, and one the repository
	 * endpoint cannot delete is not the repository's to reconcile: reporting it
	 * as extra produces a fix that always fails.
	 */
	private static List<ActualRuleset> rulesets(
			Supplier<GraphQlRepositoryResponse> graph
	) {
		return graph.get()
				.rulesets()
				.stream()
				.filter(
						rs -> rs.sourceType() != RulesetSourceType.ORGANIZATION
								&& rs.sourceType() != RulesetSourceType.ENTERPRISE
				)
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

}
