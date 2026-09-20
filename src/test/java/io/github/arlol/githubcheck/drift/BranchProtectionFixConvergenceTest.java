package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * A branch protection fix GitHub accepts leaves nothing for the next check —
 * the property {@code RulesetFixConvergenceTest} states for rulesets, for the
 * group whose write is a full replacement.
 * <p>
 * That difference is why this one matters twice over. {@code PUT
 * /repos/{owner}/{repo}/branches/{branch}/protection} replaces the whole
 * protection, so a field {@link BranchProtectionDriftGroup} compares but does
 * not put is not merely left drifted: fixing any other field on that branch
 * turns it off. A config that asks for required signatures and a linear
 * history, run through a {@code --fix} that forgot one of them, is a branch
 * that ends up with less protection than it started with, reported as FIXED.
 * <p>
 * Unlike a ruleset, the request and the response here are not the same document
 * — {@code enforce_admins} goes out as a boolean and comes back as
 * {@code {"enabled": true}}, and an actor goes out as a login and comes back as
 * an object carrying one. {@link #asResponse} is that translation and nothing
 * more; it is GitHub's documented shape for the same endpoint pair, and keeping
 * it to the shape rather than the semantics is what stops the test from quietly
 * re-implementing the code it checks. A mistake in it fails the test rather
 * than hiding a failure.
 */
@WireMockTest
class BranchProtectionFixConvergenceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(
					DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					false
			);

	private static final String URL = "/repos/owner/repo/branches/main/protection";

	/** The wrappers GitHub answers a boolean setting with. */
	private static final List<String> WRAPPED = List.of(
			"enforce_admins",
			"required_linear_history",
			"allow_force_pushes",
			"allow_deletions",
			"block_creations",
			"required_conversation_resolution",
			"lock_branch",
			"allow_fork_syncing"
	);

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		stubFor(
				put(urlEqualTo(URL)).willReturn(
						aResponse().withStatus(200)
								.withHeader("Content-Type", "application/json")
								.withBody("{\"url\": \"x\"}")
				)
		);
	}

	@Test
	void protectingABranchFromAMaximalConfigLeavesNothingToFix()
			throws Exception {
		Drifty.BranchProtection wanted = maximal();
		everyFieldDiffersFromTheSchemaDefault(wanted);

		FixResult result = group(wanted, Map.of()).detect()
				.getFirst()
				.fix()
				.execute();
		assertThat(result.unfixedItems()).isEmpty();

		assertThat(paths(wanted, Map.of("main", written()))).isEmpty();
	}

	/**
	 * The same, starting from a branch that is protected but at every default —
	 * the update path rather than the create path, and the one that would turn
	 * a setting off by omitting it.
	 */
	@Test
	void fixingAnUnprotectedBranchTurnsNothingOffThatWasAskedFor()
			throws Exception {
		Drifty.BranchProtection wanted = maximal();
		Map<String, ActualBranchProtection> bare = Map
				.of("main", ActualTypes.branchProtection(emptyResponse()));
		assertThat(paths(wanted, bare)).isNotEmpty();

		FixResult result = group(wanted, bare).detect()
				.getFirst()
				.fix()
				.execute();
		assertThat(result.unfixedItems()).isEmpty();

		assertThat(paths(wanted, Map.of("main", written()))).isEmpty();
	}

	/**
	 * {@code Drifty.BranchProtection} has no field a maximal fixture cannot
	 * set, so unlike the ruleset version this exempts nothing. A field added to
	 * {@code config/drifty.pkl} arrives here at its default and has to be given
	 * a value before the build goes green.
	 */
	private static void everyFieldDiffersFromTheSchemaDefault(
			Drifty.BranchProtection maximal
	) throws Exception {
		Drifty.BranchProtection defaults = Desired.branchProtection();
		for (Field field : Drifty.BranchProtection.class.getFields()) {
			assertThat(field.get(maximal))
					.as(
							"%s is at its schema default, so protecting this"
									+ " branch does not exercise it",
							field.getName()
					)
					.isNotEqualTo(field.get(defaults));
		}
	}

	private static Drifty.BranchProtection maximal() {
		return Desired.branchProtection()
				.withEnforceAdmins(true)
				.withRequiredLinearHistory(true)
				.withAllowForcePushes(true)
				.withAllowDeletions(true)
				.withBlockCreations(true)
				.withLockBranch(true)
				.withAllowForkSyncing(true)
				.withRequireConversationResolution(true)
				.withStrictStatusChecks(true)
				.withRequiredStatusChecks(
						List.of(Desired.statusCheck("build", 15368L))
				)
				.withRequiredApprovingReviewCount(2L)
				.withDismissStaleReviews(true)
				.withRequireCodeOwnerReviews(true)
				.withRequireLastPushApproval(true)
				.withDismissalUsers(List.of("octocat"))
				.withDismissalTeams(List.of("reviewers"))
				.withDismissalApps(List.of("dependabot"))
				.withBypassPullRequestUsers(List.of("hubot"))
				.withBypassPullRequestTeams(List.of("bots"))
				.withBypassPullRequestApps(List.of("renovate"))
				.withUsers(List.of("admin"))
				.withTeams(List.of("core"))
				.withApps(List.of("ci"));
	}

	/**
	 * The protection GitHub would return once it had applied the one PUT the
	 * fix sent.
	 */
	private static ActualBranchProtection written() throws Exception {
		List<LoggedRequest> requests = findAll(
				putRequestedFor(urlEqualTo(URL))
		);
		assertThat(requests).hasSize(1);
		ObjectNode body = (ObjectNode) MAPPER
				.readTree(requests.getFirst().getBodyAsString());
		return ActualTypes.branchProtection(
				MAPPER.treeToValue(
						asResponse(body),
						BranchProtectionResponse.class
				)
		);
	}

	/**
	 * The PUT body in the shape the GET answers with: each boolean setting
	 * wrapped in an {@code enabled} object, and each actor name expanded into
	 * the object that carries it. Everything else — {@code
	 * required_status_checks}, the review counts and flags — is spelled the
	 * same on both sides and is left alone.
	 */
	private static ObjectNode asResponse(ObjectNode request) {
		ObjectNode response = request.deepCopy();
		for (String field : WRAPPED) {
			JsonNode value = response.remove(field);
			if (value != null) {
				response.putObject(field).put("enabled", value.asBoolean());
			}
		}
		expandActors(response, "restrictions");
		JsonNode reviews = response.get("required_pull_request_reviews");
		if (reviews instanceof ObjectNode node) {
			expandActors(node, "dismissal_restrictions");
			expandActors(node, "bypass_pull_request_allowances");
		}
		return response;
	}

	private static void expandActors(ObjectNode parent, String field) {
		if (!(parent.get(field) instanceof ObjectNode actors)) {
			return;
		}
		expandNames(actors, "users", "login");
		expandNames(actors, "teams", "slug");
		expandNames(actors, "apps", "slug");
	}

	private static void expandNames(
			ObjectNode actors,
			String field,
			String nameField
	) {
		var names = actors.withArray(field);
		var expanded = MAPPER.createArrayNode();
		names.forEach(
				name -> expanded.addObject().put(nameField, name.asText())
		);
		actors.set(field, expanded);
	}

	/** A branch that is protected, with every setting GitHub can omit off. */
	private static BranchProtectionResponse emptyResponse() {
		return new BranchProtectionResponse(
				null,
				null,
				new BranchProtectionResponse.EnforceAdmins(null, false),
				new BranchProtectionResponse.RequiredLinearHistory(false),
				new BranchProtectionResponse.AllowForcePushes(false),
				null,
				null,
				null,
				new BranchProtectionResponse.RequiredStatusChecks(
						null,
						null,
						false,
						List.of(),
						null,
						null
				),
				null,
				null,
				"main",
				null,
				null,
				null,
				null
		);
	}

	private BranchProtectionDriftGroup group(
			Drifty.BranchProtection wanted,
			Map<String, ActualBranchProtection> actual
	) {
		return new BranchProtectionDriftGroup(
				Map.of("main", wanted),
				actual,
				client,
				new RepoRef("owner", "repo")
		);
	}

	private static List<String> paths(
			Drifty.BranchProtection wanted,
			Map<String, ActualBranchProtection> actual
	) {
		return new BranchProtectionDriftGroup(
				Map.of("main", wanted),
				actual,
				null,
				new RepoRef("owner", "repo")
		).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.map(DriftItem::path)
				.toList();
	}

}
