package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>
 * <strong>Depth is read off the order requests arrive in, not off the
 * clock.</strong> An earlier version bucketed arrival times into multiples of
 * the stub delay; it passed locally and failed on CI's slower macOS runner,
 * which smeared the arrivals across ~300ms and pushed one into the next bucket.
 * Every stub answers after {@link #DELAY_MILLIS}, so a read that waits on a
 * listing cannot arrive until that listing has answered, and each tier below is
 * asserted to have gone out in full before the next one started.
 */
class OrganizationCheckerRequestShapeTest {

	/**
	 * Long enough that issuing every request of one tier comfortably fits
	 * inside it on a loaded three-core runner.
	 */
	private static final int DELAY_MILLIS = 700;

	/**
	 * Every endpoint {@code fetchState} reads for an organization with
	 * {@code selected} Actions permissions, one selected secret, one selected
	 * variable, two rulesets, one code security configuration, two teams and
	 * one selected runner group.
	 */
	private static final int ORGANIZATION_REQUESTS = 26;

	/**
	 * The existence check every group waits on.
	 */
	private static final String EXISTENCE_CHECK = "/orgs/acme";

	/**
	 * The reads that may not go out until a listing has named what they ask
	 * for. Everything else is a group's own listing or a flat read, and goes
	 * out as soon as the organization is known to exist.
	 * <p>
	 * A new group whose read waits on another read belongs here; one that reads
	 * an endpoint outright does not. Getting that wrong is the point — it fails
	 * here rather than quietly adding a round trip to the head of every run.
	 */
	private static final Set<String> WAITS_ON_A_LISTING = Set.of(
			"/orgs/acme/actions/permissions/selected-actions",
			"/orgs/acme/actions/permissions/repositories",
			"/orgs/acme/actions/secrets/PAT/repositories",
			"/orgs/acme/actions/variables/REGION/repositories",
			"/orgs/acme/rulesets/1",
			"/orgs/acme/rulesets/2",
			"/orgs/acme/code-security/configurations/defaults",
			"/orgs/acme/code-security/configurations/9/repositories",
			"/orgs/acme/teams/one/members",
			"/orgs/acme/teams/two/members",
			"/orgs/acme/actions/runner-groups/5/repositories"
	);

	/** Thirteen requests sit at the deepest tier; two teams answer twice. */
	private static final int WAITING_READS = 13;

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					// Well above the requests one check sends here, so the
					// only thing that can serialize them is drifty.
					wireMockConfig().dynamicPort().containerThreads(100)
			)
			.build();

	/**
	 * One fetch, three properties. They share it because each one costs two
	 * stub delays and nothing about them needs a fresh organization.
	 */
	@Test
	void anOrganizationWaitsOnItsExistenceCheckAndOneListing() {
		stubOrganization();

		new OrganizationChecker(
				new GitHubClient(wm.getRuntimeInfo().getHttpBaseUrl(), "t"),
				false,
				Map.of(),
				new DriftyState()
		).fetchState("acme", ManagedGroups.all(Drifty.OrgGroupName.class));

		List<String> order = arrivalOrder();

		assertThat(order).as("what the organization's shape costs")
				.hasSize(ORGANIZATION_REQUESTS);

		assertThat(order).as("the existence check goes first, alone")
				.element(0)
				.isEqualTo(EXISTENCE_CHECK);

		assertThat(firstWaitingRead(order)).as(
				"every listing and flat read goes out before any read that waits on one"
		).isEqualTo(ORGANIZATION_REQUESTS - WAITING_READS);

		// Guards the guard: sent one at a time, the ordering above holds
		// trivially with one request per tier. What says the fan-out is real is
		// that the listings overlapped.
		assertThat(maxInFlight()).as("the group listings overlap")
				.isGreaterThanOrEqualTo(8);
	}

	// ─── Reading the serve events
	// ──────────────────────────────────────

	/**
	 * How many requests arrived before the first one that had to wait for a
	 * listing. With the fan-out intact that is the existence check plus every
	 * listing and flat read; re-serialize {@code fetchState} and it collapses.
	 */
	private static int firstWaitingRead(List<String> order) {
		for (int i = 0; i < order.size(); i++) {
			if (WAITS_ON_A_LISTING.contains(order.get(i))) {
				return i;
			}
		}
		return order.size();
	}

	/** Every request's path, in the order GitHub received it. */
	private static List<String> arrivalOrder() {
		return events().stream().map(LoggedRequest::getUrl).map(url -> {
			int query = url.indexOf('?');
			return query < 0 ? url : url.substring(0, query);
		}).toList();
	}

	/**
	 * The most requests in flight at the same moment. WireMock logs a request
	 * when it arrives and holds it for {@link #DELAY_MILLIS}, so requests
	 * logged inside one delay of each other overlapped.
	 */
	private static int maxInFlight() {
		List<Long> received = events().stream()
				.map(request -> request.getLoggedDate().getTime())
				.toList();
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

	private static List<LoggedRequest> events() {
		return wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
				.toList();
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
