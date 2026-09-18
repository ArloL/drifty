package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * What an organization costs GitHub, the way
 * {@link RepositoryCheckerRequestShapeTest} asks it of a repository, and for
 * the same reason: request count and critical-path depth are deterministic
 * where wall clock is not.
 * <p>
 * The organization is one entity, not one of a hundred, so its own chain is
 * pure head — {@code GitHubCheck.check} runs an organization before its
 * repositories, and every round trip here is one no repository has started yet.
 * Sent one after another, the fixture below was twenty-four of them.
 * <p>
 * Its budget is one level deeper than a repository's. {@code GET /orgs/{org}}
 * is how {@code fetchState} learns the organization exists — a 404 there is
 * what makes the entry {@code MISSING} — so every group waits on it rather than
 * firing two dozen requests at a login GitHub has never heard of.
 */
class OrganizationCheckerRequestShapeTest {

	private static final int DELAY_MILLIS = 200;

	/**
	 * {@code GET /orgs/{org}}, then a group listing, then the reads that name
	 * something a listing returned. Nothing may sit below that.
	 */
	private static final int MAX_ROUND_TRIPS = 3;

	/**
	 * Every endpoint {@code fetchState} reads for an organization with
	 * {@code selected} Actions permissions, one selected secret, one selected
	 * variable, two rulesets, one code security configuration, two teams and
	 * one selected runner group.
	 */
	private static final int ORGANIZATION_REQUESTS = 26;

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					// Well above the requests one check sends here, so the
					// only thing that can serialize them is drifty.
					wireMockConfig().dynamicPort().containerThreads(100)
			)
			.build();

	private OrganizationChecker checker;

	@BeforeEach
	void setUp() {
		checker = new OrganizationChecker(
				new GitHubClient(wm.getRuntimeInfo().getHttpBaseUrl(), "t"),
				false,
				Map.of(),
				new DriftyState()
		);
		stubOrganization();
	}

	@Test
	void anOrganizationCostsTheRequestsItsShapeNeeds() {
		fetch();

		assertThat(received()).hasSize(ORGANIZATION_REQUESTS);
	}

	@Test
	void noGroupWaitsOnMoreThanTheExistenceCheckAndItsOwnListing() {
		fetch();

		assertThat(roundTrips()).isLessThanOrEqualTo(MAX_ROUND_TRIPS);
	}

	/**
	 * Guards the guard: a checker that sent these one at a time would report
	 * one request per round-trip bucket and satisfy nothing. What says the
	 * fan-out is real is that most of them were in flight together.
	 */
	@Test
	void theGroupsThatWaitOnNothingAreAllInFlightTogether() {
		fetch();

		assertThat(maxInFlight()).isGreaterThanOrEqualTo(8);
	}

	private void fetch() {
		checker.fetchState(
				"acme",
				ManagedGroups.all(Drifty.OrgGroupName.class)
		);
	}

	// ─── Reading the serve events
	// ──────────────────────────────────────

	private static List<Long> received() {
		List<Long> times = new ArrayList<>();
		wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
				.forEach(
						request -> times.add(request.getLoggedDate().getTime())
				);
		return times;
	}

	/**
	 * How many round trips deep the reads go, by bucketing every request's
	 * arrival into multiples of {@link #DELAY_MILLIS} from the first. A request
	 * that waited on nothing but the existence check lands in bucket 1; one
	 * that waited on a listing lands in bucket 2.
	 */
	private static int roundTrips() {
		List<Long> times = received();
		long first = times.stream().min(Long::compare).orElseThrow();
		return (int) times.stream()
				.mapToLong(
						at -> Math.round((at - first) / (double) DELAY_MILLIS)
				)
				.max()
				.orElseThrow() + 1;
	}

	/**
	 * The most requests in flight at the same moment. WireMock logs a request
	 * when it arrives and holds it for {@link #DELAY_MILLIS}, so requests
	 * logged inside one delay of each other overlapped.
	 */
	private static int maxInFlight() {
		List<Long> times = received();
		return times.stream()
				.mapToInt(
						start -> (int) times.stream()
								.filter(
										other -> other >= start
												&& other < start + DELAY_MILLIS
								)
								.count()
				)
				.max()
				.orElseThrow();
	}

	// ─── The organization
	// ──────────────────────────────────────────────

	/**
	 * Every group turned on, and every group that has a second level given
	 * something to read there: {@code selected} everywhere it is a choice, two
	 * rulesets, two teams and one configuration.
	 */
	private static void stubOrganization() {
		stub("/orgs/acme", """
				{"login": "acme", "billing_email": "b@acme.test"}
				""");
		stub(
				"/orgs/acme/actions/permissions",
				"""
						{"enabled_repositories": "selected", "allowed_actions": "selected"}
						"""
		);
		stub(
				"/orgs/acme/actions/permissions/selected-actions",
				"""
						{"github_owned_allowed": true, "verified_allowed": false, "patterns_allowed": []}
						"""
		);
		stub("/orgs/acme/actions/permissions/repositories", """
				{"repositories": []}
				""");
		stub(
				"/orgs/acme/actions/permissions/workflow",
				"""
						{"default_workflow_permissions": "read", "can_approve_pull_request_reviews": false}
						"""
		);
		stub("/orgs/acme/actions/secrets", """
				{"secrets": [{"name": "PAT", "visibility": "selected"}]}
				""");
		stub("/orgs/acme/actions/secrets/PAT/repositories", """
				{"repositories": []}
				""");
		stub(
				"/orgs/acme/actions/variables",
				"""
						{"variables": [{"name": "REGION", "value": "eu", "visibility": "selected"}]}
						"""
		);
		stub("/orgs/acme/actions/variables/REGION/repositories", """
				{"repositories": []}
				""");
		stub("/orgs/acme/hooks", "[]");
		stub("/orgs/acme/properties/schema", "[]");
		stub("/orgs/acme/rulesets", """
				[{"id": 1, "name": "one"}, {"id": 2, "name": "two"}]
				""");
		stub("/orgs/acme/rulesets/1", """
				{"id": 1, "name": "one", "rules": []}
				""");
		stub("/orgs/acme/rulesets/2", """
				{"id": 2, "name": "two", "rules": []}
				""");
		stub("/orgs/acme/code-security/configurations", """
				[{"id": 9, "name": "baseline", "target_type": "organization"}]
				""");
		stub("/orgs/acme/code-security/configurations/defaults", "[]");
		stub("/orgs/acme/code-security/configurations/9/repositories", "[]");
		stub(
				"/orgs/acme/teams",
				"""
						[{"id": 1, "name": "one", "slug": "one"}, {"id": 2, "name": "two", "slug": "two"}]
						"""
		);
		wm.stubFor(
				get(urlPathMatching("/orgs/acme/teams/(one|two)/members"))
						.willReturn(delayed("[]"))
		);
		wm.stubFor(
				get(urlPathEqualTo("/orgs/acme/members"))
						.willReturn(delayed("[]"))
		);
		stub(
				"/orgs/acme/actions/runner-groups",
				"""
						{"runner_groups": [{"id": 5, "name": "shared", "visibility": "selected"}]}
						"""
		);
		stub("/orgs/acme/actions/runner-groups/5/repositories", """
				{"repositories": []}
				""");
	}

	private static void stub(String path, String body) {
		wm.stubFor(get(urlPathEqualTo(path)).willReturn(delayed(body)));
	}

	private static ResponseDefinitionBuilder delayed(String body) {
		return okJson(body).withFixedDelay(DELAY_MILLIS);
	}

}
