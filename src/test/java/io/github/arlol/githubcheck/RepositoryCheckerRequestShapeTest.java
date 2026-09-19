package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * the ordering assertion below is what would have caught that the day it
 * appeared, and what stops a new drift group from quietly re-serializing
 * {@link RepositoryChecker#fetchState}.
 * <p>
 * <strong>Depth is read off the order requests arrive in, not off the
 * clock.</strong> An earlier version bucketed arrival times into multiples of
 * the stub delay and asserted the bucket count; it passed locally and failed on
 * CI's slower macOS runner, where scheduling smeared one repository's requests
 * across ~300ms and pushed a request into the next bucket. Every stub answers
 * after {@link #DELAY_MILLIS}, so a read that waits on a listing cannot arrive
 * until that listing has answered — and the only assumption left is that the
 * requests waiting on nothing are all <em>sent</em> within one delay, which is
 * a burst of socket writes rather than anything the runner has to schedule
 * fairly.
 */
class RepositoryCheckerRequestShapeTest {

	/**
	 * Long enough that issuing every independent request comfortably fits
	 * inside it on a loaded three-core runner. The suite pays it twice — once
	 * for each level.
	 */
	private static final int DELAY_MILLIS = 700;

	/**
	 * Every request {@code fetchState} sends for a public, organization-owned
	 * repository with one protected branch, one ruleset, one environment and a
	 * Pages site: the GraphQL query, the repository's own details, three
	 * security flags, the environment listing with two reads below it, Actions
	 * secrets and variables, workflow permissions, Pages, webhooks, custom
	 * property values and its teams.
	 * <p>
	 * Three security flags rather than five. Automated security fixes are
	 * {@code security_and_analysis.dependabot_security_updates} on the details
	 * response, which is read for eight other security groups anyway, and
	 * vulnerability alerts come from the GraphQL query. That query also answers
	 * the rulesets and their rules, the branch protections, and the
	 * collaborators — six requests and both of the repository's two-level
	 * chains, in one. Pages is here only because the details response says
	 * {@code has_pages}; a repository with no site is not asked, which is the
	 * case {@code RepositoryCheckerFetchStateTest} pins.
	 */
	private static final int ACTIVE_REPOSITORY_REQUESTS = 15;

	/**
	 * A repository the config wants archived is compared on {@code archived}
	 * alone — {@code createDriftGroups} returns that group and no other — and
	 * the account listing already carries that boolean, so {@code checkOne}
	 * sends nothing at all. It used to read every group and drop the answers
	 * (49 archived repositories of one 101-repository account spent 343 of its
	 * 1152 requests that way), then its own details and drop all but one field
	 * of that (51 more).
	 */
	private static final int ARCHIVED_REPOSITORY_REQUESTS = 0;

	/**
	 * The seven reads that may not go out until something else has answered: a
	 * branch's protection and a ruleset's rules on the listing that named them,
	 * an environment's secrets and variables on the environment listing, and on
	 * {@code GET /repos/{owner}/{repo}} the two endpoints that exist only under
	 * an organization — it is what says whether one owns it — and Pages, which
	 * it is what says there is a site to ask about.
	 * <p>
	 * A new group whose read waits on another read belongs in this set, and a
	 * new group that reads an endpoint outright does not. Getting that wrong is
	 * the point: it fails here rather than quietly adding a round trip to every
	 * repository in an account.
	 */
	private static final Set<String> WAITS_ON_AN_EARLIER_READ = Set.of(
			"/repos/acme/active/branches/main/protection",
			"/repos/acme/active/rulesets/42",
			"/repos/acme/active/environments/prod/secrets",
			"/repos/acme/active/environments/prod/variables",
			"/repos/acme/active/properties/values",
			"/repos/acme/active/teams",
			"/repos/acme/active/pages"
	);

	private static final Pattern REPOSITORY_PATH = Pattern
			.compile("/repos/acme/([^/]+)");

	/** How the GraphQL query names the repository it is asking about. */
	private static final Pattern GRAPHQL_REPOSITORY = Pattern
			.compile("name: \\\\\"([^\\\\\"]+)\\\\\"");

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					// Well above the requests one check sends here, so the
					// only thing that can serialize them is drifty.
					wireMockConfig().dynamicPort().containerThreads(100)
			)
			.build();

	/**
	 * One check, three properties. They share a fetch because each one costs
	 * two stub delays, and nothing about them needs a fresh account.
	 */
	@Test
	void aCheckSendsEachRepositoryOneLevelOfWaitingAndNoMore()
			throws Exception {
		GitHubClient client = new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"t"
		);
		stubAccount();

		new RepositoryChecker(client, false).check(
				"acme",
				client.listOrgRepos("acme").orElseThrow(),
				List.of(
						Desired.repository("active"),
						Desired.repository("frozen").withArchived(true)
				)
		);

		// "frozen" is absent rather than present with a zero: nothing was
		// sent under it, so there is no serve event to count.
		assertThat(requestsPerRepository())
				.as("what each repository's shape costs")
				.containsExactlyInAnyOrderEntriesOf(
						Map.of("active", ACTIVE_REPOSITORY_REQUESTS)
				);

		assertThat(firstWaitingRead()).as(
				"every read that waits on nothing goes out before any read that waits"
		).isGreaterThanOrEqualTo(independentReads());

		// Guards the guard: a checker that sent these one at a time would
		// satisfy the ordering above trivially, every "level" holding one
		// request. What says the fan-out is real is that they overlapped.
		assertThat(maxInFlight("active"))
				.as("one repository's independent reads overlap")
				.isGreaterThanOrEqualTo(10);
	}

	// ─── Reading the serve events
	// ──────────────────────────────────────

	/**
	 * How many requests arrived before the first one that had to wait for an
	 * earlier read. With the fan-out intact this is every independent request;
	 * re-serialize {@code fetchState} and it collapses to a handful.
	 */
	private static int firstWaitingRead() {
		List<String> order = arrivalOrder();
		int first = order.size();
		for (int i = 0; i < order.size(); i++) {
			if (WAITS_ON_AN_EARLIER_READ.contains(order.get(i))) {
				first = i;
				break;
			}
		}
		return first;
	}

	/** The requests of both repositories that wait on nothing. */
	private static int independentReads() {
		return ACTIVE_REPOSITORY_REQUESTS + ARCHIVED_REPOSITORY_REQUESTS
				- WAITS_ON_AN_EARLIER_READ.size();
	}

	/** Every repository request's path, in the order GitHub received it. */
	private static List<String> arrivalOrder() {
		return events().stream().map(LoggedRequest::getUrl).map(url -> {
			int query = url.indexOf('?');
			return query < 0 ? url : url.substring(0, query);
		}).toList();
	}

	private static Map<String, Integer> requestsPerRepository() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		events().forEach(request -> {
			String repository = repositoryOf(request);
			if (repository != null) {
				counts.merge(repository, 1, Integer::sum);
			}
		});
		return counts;
	}

	/**
	 * Which repository a request was sent for. The GraphQL query names it in
	 * its body rather than its URL, and it counts against the repository like
	 * any other request it replaced.
	 */
	private static String repositoryOf(LoggedRequest request) {
		Matcher path = REPOSITORY_PATH.matcher(request.getUrl());
		if (path.lookingAt()) {
			return path.group(1);
		}
		Matcher named = GRAPHQL_REPOSITORY.matcher(request.getBodyAsString());
		return named.find() ? named.group(1) : null;
	}

	/**
	 * The most of one repository's requests that were in flight at the same
	 * moment. WireMock logs a request when it arrives and holds it for
	 * {@link #DELAY_MILLIS}, so requests logged inside one delay of each other
	 * overlapped.
	 */
	private static int maxInFlight(String repository) {
		List<Long> received = new ArrayList<>();
		events().forEach(request -> {
			if (repository.equals(repositoryOf(request))) {
				received.add(request.getLoggedDate().getTime());
			}
		});
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
	 * Every repository request, oldest first. The account listing is not one of
	 * them — it belongs to no repository and is what the checker starts from.
	 */
	private static List<LoggedRequest> events() {
		return wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.filter(request -> repositoryOf(request) != null)
				.sorted(Comparator.comparing(LoggedRequest::getLoggedDate))
				.collect(Collectors.toList());
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

	/**
	 * The one query for four groups, and the listing whose reads still wait on
	 * it.
	 */
	private static void stubListings() {
		wm.stubFor(post(urlPathEqualTo("/graphql")).willReturn(delayed("""
				{"data": {
				  "rs": {"rulesets": {"nodes": [{
				      "databaseId": 42, "name": "main-rules",
				      "target": "BRANCH", "enforcement": "ACTIVE",
				      "source": {"__typename": "Repository"},
				      "bypassActors": {"nodes": []},
				      "rules": {"nodes": []}
				  }]}},
				  "bp": {"branchProtectionRules": {"nodes": [{
				      "pattern": "main",
				      "matchingRefs": {"nodes": [{"name": "main"}]},
				      "requiresStatusChecks": false,
				      "requiresApprovingReviews": false,
				      "restrictsPushes": false
				  }]}},
				  "co": {"collaborators": {"edges": []}},
				  "va": {"hasVulnerabilityAlertsEnabled": false}
				}}
				""")));
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
					"has_pages": true,
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
