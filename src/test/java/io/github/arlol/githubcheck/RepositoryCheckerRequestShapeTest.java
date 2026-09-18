package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * What a check costs GitHub, asserted over WireMock's serve events.
 * <p>
 * Not wall clock: ~95% of a real check is someone else's network, so a timing
 * assertion would flake without saying anything. The two things that actually
 * regress are deterministic — how many requests one repository costs, and how
 * many of them wait on each other. A run of the {@code ArloL} account was 1152
 * requests and 9.5s, of which one repository's own 24-request chain was 6.6s;
 * the depth assertion below is what would have caught that the day it appeared,
 * and what stops a new drift group from quietly re-serializing
 * {@link RepositoryChecker#fetchState}.
 * <p>
 * Every stub answers after {@link #DELAY_MILLIS}, which is what makes the two
 * levels of the fan-out legible: everything independent is logged within a few
 * milliseconds of the repository's first request, and the five reads that wait
 * on a listing are logged one delay later. A chain of <em>n</em> requests
 * spreads over <em>n</em> delays and fails
 * {@link #noRepositoryWaitsOnMoreThanOneRoundTrip}.
 * <p>
 * The counts in {@link #eachRepositoryCostsTheRequestsItsShapeNeeds} are exact
 * on purpose. A new managed setting that adds an endpoint is supposed to fail
 * this test: the number here is the budget, and changing it is the decision to
 * spend one more request on every repository in an account.
 */
class RepositoryCheckerRequestShapeTest {

	private static final int DELAY_MILLIS = 200;

	/**
	 * Every endpoint {@code fetchState} reads for a public, organization-owned
	 * repository with one protected branch, one ruleset and one environment:
	 * the repository's own details, five security flags, the branch and ruleset
	 * and environment listings with one read each below them, Actions secrets
	 * and variables, that environment's secrets and variables, workflow
	 * permissions, Pages, webhooks, custom property values, its collaborators
	 * and its teams.
	 */
	private static final int ACTIVE_REPOSITORY_REQUESTS = 21;

	/**
	 * A repository the config wants archived is compared on {@code archived}
	 * alone — {@code createDriftGroups} returns that group and no other — so
	 * the only thing left to read is the repository itself. It used to read
	 * every group and drop the answers: 49 archived repositories of one
	 * 101-repository account spent 343 of its 1152 requests that way.
	 */
	private static final int ARCHIVED_REPOSITORY_REQUESTS = 1;

	private static final Pattern REPOSITORY_PATH = Pattern
			.compile("/repos/acme/([^/]+)");

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					// Well above the requests one check sends here, so the
					// only thing that can serialize them is drifty.
					wireMockConfig().dynamicPort().containerThreads(100)
			)
			.build();

	private GitHubClient client;
	private RepositoryChecker checker;

	@BeforeEach
	void setUp() {
		client = new GitHubClient(wm.getRuntimeInfo().getHttpBaseUrl(), "t");
		checker = new RepositoryChecker(client, false);
		stubAccount();
	}

	@Test
	void eachRepositoryCostsTheRequestsItsShapeNeeds() throws Exception {
		check();

		assertThat(requestsPerRepository()).containsExactlyInAnyOrderEntriesOf(
				Map.of(
						"active",
						ACTIVE_REPOSITORY_REQUESTS,
						"frozen",
						ARCHIVED_REPOSITORY_REQUESTS
				)
		);
	}

	@Test
	void noRepositoryWaitsOnMoreThanOneRoundTrip() throws Exception {
		check();

		assertThat(roundTripsPerRepository()).allSatisfy(
				(repository, depth) -> assertThat(depth).as(repository)
						.isLessThanOrEqualTo(2)
		);
	}

	/**
	 * Guards the guard: a checker that sent one repository's requests one at a
	 * time would satisfy every count above and report a depth of 1 per
	 * round-trip bucket only because each bucket held a single request. What
	 * says the fan-out is real is that most of {@code active}'s requests were
	 * in flight together.
	 */
	@Test
	void oneRepositorysIndependentRequestsAreAllInFlightTogether()
			throws Exception {
		check();

		assertThat(maxInFlight("active"))
				.isGreaterThanOrEqualTo(ACTIVE_REPOSITORY_REQUESTS - 6);
	}

	private void check() throws Exception {
		checker.check(
				"acme",
				client.listOrgRepos("acme").orElseThrow(),
				List.of(
						Desired.repository("active"),
						Desired.repository("frozen").withArchived(true)
				)
		);
	}

	// ─── Reading the serve events
	// ──────────────────────────────────────

	private static Map<String, Integer> requestsPerRepository() {
		return repositoryEvents().entrySet()
				.stream()
				.collect(
						Collectors.toMap(
								Map.Entry::getKey,
								e -> e.getValue().size()
						)
				);
	}

	/**
	 * How many round trips deep each repository's reads go, by bucketing every
	 * request's arrival into multiples of {@link #DELAY_MILLIS} from that
	 * repository's first. A request that waited on nothing lands in bucket 0
	 * with the rest; one that waited on a listing lands in bucket 1, a delay
	 * later. The bucket count is the depth.
	 */
	private static Map<String, Integer> roundTripsPerRepository() {
		Map<String, Integer> depths = new LinkedHashMap<>();
		repositoryEvents().forEach((repository, received) -> {
			long first = received.stream().min(Long::compare).orElseThrow();
			long deepest = received.stream()
					.mapToLong(
							at -> Math
									.round((at - first) / (double) DELAY_MILLIS)
					)
					.max()
					.orElseThrow();
			depths.put(repository, (int) deepest + 1);
		});
		return depths;
	}

	/**
	 * The most of one repository's requests that were in flight at the same
	 * moment. WireMock logs a request when it arrives and holds it for
	 * {@link #DELAY_MILLIS}, so requests logged inside one delay of each other
	 * overlapped.
	 */
	private static int maxInFlight(String repository) {
		List<Long> received = repositoryEvents().get(repository);
		return received.stream()
				.mapToInt(
						start -> (int) received.stream()
								.filter(
										other -> other >= start
												&& other < start + DELAY_MILLIS
								)
								.count()
				)
				.max()
				.orElseThrow();
	}

	/**
	 * When each repository's requests arrived, keyed by the name in the path.
	 * The account listing is not one of them — it belongs to no repository and
	 * is what the checker starts from.
	 */
	private static Map<String, List<Long>> repositoryEvents() {
		Map<String, List<Long>> byRepository = new LinkedHashMap<>();
		wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
				.forEach(request -> {
					Matcher path = REPOSITORY_PATH.matcher(request.getUrl());
					if (path.lookingAt()) {
						byRepository
								.computeIfAbsent(
										path.group(1),
										_ -> new ArrayList<>()
								)
								.add(request.getLoggedDate().getTime());
					}
				});
		return byRepository;
	}

	// ─── The account
	// ─────────────────────────────────────────────────────

	/**
	 * One organization with two repositories: an active public one, which reads
	 * every group, and an archived one the config also wants archived, which
	 * reads none.
	 */
	private static void stubAccount() {
		wm.stubFor(
				get(urlPathEqualTo("/orgs/acme/repos")).willReturn(
						delayed(
								"""
										[
										  {"name": "active", "archived": false, "visibility": "public"},
										  {"name": "frozen", "archived": true, "visibility": "public"}
										]
										"""
						)
				)
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+"))
						.willReturn(delayed(details()))
		);
		stubSecurityFlags();
		stubListings();
		stubFlatReads();
	}

	private static void stubSecurityFlags() {
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/vulnerability-alerts"))
						.willReturn(delayedStatus(204))
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/automated-security-fixes"
						)
				).willReturn(delayed("{\"enabled\": false}"))
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/immutable-releases"))
						.willReturn(delayed("{\"enabled\": false}"))
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/private-vulnerability-reporting"
						)
				).willReturn(delayed("{\"enabled\": false}"))
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/code-scanning/default-setup"
						)
				).willReturn(delayed("{\"state\": \"not-configured\"}"))
		);
	}

	/** The three listings, and the one read each of them leads to. */
	private static void stubListings() {
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/branches")).willReturn(
						delayed("[{\"name\": \"main\", \"protected\": true}]")
				)
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/branches/main/protection"
						)
				).willReturn(
						delayed(
								"""
										{
										  "enforce_admins": {"enabled": false},
										  "required_linear_history": {"enabled": false},
										  "allow_force_pushes": {"enabled": false},
										  "allow_deletions": {"enabled": false},
										  "block_creations": {"enabled": false},
										  "required_conversation_resolution": {"enabled": false},
										  "lock_branch": {"enabled": false},
										  "allow_fork_syncing": {"enabled": false}
										}
										"""
						)
				)
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/rulesets")).willReturn(
						delayed("[{\"id\": 42, \"name\": \"main-rules\"}]")
				)
		);
		wm.stubFor(
				get(
						urlPathMatching("/repos/acme/[^/]+/rulesets/42")
				).willReturn(
						delayed(
								"{\"id\": 42, \"name\": \"main-rules\", \"rules\": []}"
						)
				)
		);
		wm.stubFor(
				get(
						urlPathMatching("/repos/acme/[^/]+/environments")
				).willReturn(
						delayed("{\"environments\": [{\"name\": \"prod\"}]}")
				)
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/environments/prod/secrets"
						)
				).willReturn(delayed("{\"secrets\": []}"))
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/environments/prod/variables"
						)
				).willReturn(delayed("{\"variables\": []}"))
		);
	}

	private static void stubFlatReads() {
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/actions/secrets"))
						.willReturn(delayed("{\"secrets\": []}"))
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/actions/variables"))
						.willReturn(delayed("{\"variables\": []}"))
		);
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/acme/[^/]+/actions/permissions/workflow"
						)
				).willReturn(
						delayed(
								"{\"default_workflow_permissions\": \"write\","
										+ " \"can_approve_pull_request_reviews\": true}"
						)
				)
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/hooks"))
						.willReturn(delayed("[]"))
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/pages"))
						.willReturn(delayedStatus(404))
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/properties/values"))
						.willReturn(delayed("[]"))
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/collaborators"))
						.willReturn(delayed("[]"))
		);
		wm.stubFor(
				get(urlPathMatching("/repos/acme/[^/]+/teams"))
						.willReturn(delayed("[]"))
		);
	}

	private static ResponseDefinitionBuilder delayed(String body) {
		return okJson(body).withFixedDelay(DELAY_MILLIS);
	}

	private static ResponseDefinitionBuilder delayedStatus(int status) {
		return aResponse().withStatus(status).withFixedDelay(DELAY_MILLIS);
	}

	/**
	 * Organization-owned, which is what makes {@code /teams} and
	 * {@code /properties/values} worth sending — the two reads that wait on
	 * this response rather than on a listing.
	 */
	private static String details() {
		return """
				{
					"id": 1,
					"name": "repo",
					"owner": {"login": "acme", "type": "Organization"},
					"private": false,
					"fork": false,
					"archived": false,
					"disabled": false,
					"is_template": false,
					"visibility": "public",
					"default_branch": "main",
					"has_issues": true,
					"has_projects": true,
					"has_wiki": true,
					"has_discussions": false,
					"has_pages": false,
					"allow_forking": true,
					"web_commit_signoff_required": false,
					"allow_squash_merge": true,
					"allow_merge_commit": true,
					"allow_rebase_merge": true,
					"allow_auto_merge": false,
					"delete_branch_on_merge": false,
					"allow_update_branch": false
				}
				""";
	}

}
