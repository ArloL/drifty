package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * A fix GitHub accepts leaves nothing for the next check.
 * <p>
 * Every other test here stops at the request: it asserts that a drifted field
 * produced a PUT, and sometimes that the PUT carried the field. None of them
 * asks the question an operator actually asks, which is whether running
 * {@code --fix} makes the drift go away. A ruleset field the comparison reports
 * but {@link RulesetComparison#request} never writes is the shape that gap
 * allows: drifty reports the drift, sends a PUT that GitHub accepts, prints the
 * setting as FIXED, and reports it again on the next run — forever, with no
 * error anywhere. Forty-odd fields translate through that one method and
 * nothing was checking that the translation is onto.
 * <p>
 * The simulation is the request body read back as a response. That is not an
 * approximation of GitHub: {@code POST}/{@code PUT} and {@code GET} on a
 * ruleset carry the same document, which is why {@link RulesetRequest} and
 * {@link RulesetDetailsResponse} are near-mirrors, and
 * {@code GitHubApiContractTest} holds both to the same endpoint's spec. What
 * the round trip does not model is GitHub rejecting or normalising a value —
 * which rules a target accepts is not checked anywhere, by design, and GitHub's
 * 422 is the report.
 * <p>
 * The cases come from {@link RulesetDriftGroupTest}'s own per-field table
 * rather than a second fixture, so the two directions cannot fall out of step:
 * a row added there — one field, drifted, asserted to be reported — becomes a
 * row here asking whether fixing it sticks. The precondition assertion is what
 * makes that safe, since a case that stopped drifting would otherwise converge
 * trivially.
 */
@WireMockTest
class RulesetFixConvergenceTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(
					DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					false
			);

	private static final String URL = "/repos/owner/repo/rulesets/1";
	private static final String CREATE_URL = "/repos/owner/repo/rulesets";

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		stubFor(
				put(urlEqualTo(URL)).willReturn(
						aResponse().withStatus(200)
								.withHeader("Content-Type", "application/json")
								.withBody("{\"id\": 1, \"name\": \"rs\"}")
				)
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("cases")
	void aFixGitHubAcceptsLeavesNothingForTheNextCheck(
			String path,
			Drifty.Ruleset wanted,
			ActualRuleset actual
	) throws Exception {
		assertThat(paths(wanted, actual))
				.as("the case has to drift before fixing it means anything")
				.containsExactly(path);

		FixResult result = group(wanted, actual).detect()
				.getFirst()
				.fix()
				.execute();
		assertThat(result.unfixedItems()).isEmpty();

		assertThat(paths(wanted, written(putRequestedFor(urlEqualTo(URL)))))
				.isEmpty();
	}

	static Stream<Arguments> cases() {
		return RulesetDriftGroupTest.driftInOneFieldIsReportedAsThatField();
	}

	/**
	 * The same property for a ruleset GitHub does not have yet, and for every
	 * field at once.
	 * <p>
	 * The table above is eighteen rows against {@code Drifty.Ruleset}'s
	 * twenty-eight fields, because it was written from what mutation testing
	 * found rather than from the schema — so the fields it misses are exactly
	 * the ones where a missing write would still be invisible. Setting all of
	 * them on one ruleset and creating it covers those, and covers the create
	 * path, which takes the same {@code RulesetComparison.request} through a
	 * different endpoint.
	 * <p>
	 * {@link #everyFieldDiffersFromTheSchemaDefault} is what keeps it that way:
	 * a field added to {@code config/drifty.pkl} arrives here at its default,
	 * fails that assertion, and has to be given a value before the build goes
	 * green — the same rule {@code SchemaCoverageTest} applies to the export.
	 * Without it this fixture would quietly stop being maximal on the first
	 * schema change.
	 */
	@Test
	void creatingARulesetFromAMaximalConfigLeavesNothingToFix()
			throws Exception {
		stubFor(
				post(urlEqualTo(CREATE_URL)).willReturn(
						aResponse().withStatus(201)
								.withHeader("Content-Type", "application/json")
								.withBody("{\"id\": 1, \"name\": \"rs\"}")
				)
		);
		Drifty.Ruleset wanted = maximal();
		everyFieldDiffersFromTheSchemaDefault(wanted);

		FixResult result = new RulesetDriftGroup(
				Map.of("rs", wanted),
				List.of(),
				client,
				new RepoRef("owner", "repo")
		).detect().getFirst().fix().execute();
		assertThat(result.unfixedItems()).isEmpty();

		assertThat(
				paths(wanted, written(postRequestedFor(urlEqualTo(CREATE_URL))))
		).isEmpty();
	}

	/**
	 * {@code target} is the one field left at its default, and it has to be:
	 * the schema refuses {@code includePatterns} and {@code excludePatterns} on
	 * any target but {@code branch} and {@code tag}, so a fixture that moved it
	 * could not also carry those two. {@code tag} would satisfy both — it is
	 * {@code branch} here only because a branch ruleset is the target every
	 * other rule in the fixture is legal on, and this test does not model
	 * GitHub refusing a rule.
	 */
	private static void everyFieldDiffersFromTheSchemaDefault(
			Drifty.Ruleset maximal
	) throws Exception {
		Drifty.Ruleset defaults = Desired.ruleset();
		for (Field field : Drifty.Ruleset.class.getFields()) {
			if ("target".equals(field.getName())) {
				continue;
			}
			assertThat(field.get(maximal))
					.as(
							"%s is at its schema default, so creating this"
									+ " ruleset does not exercise it",
							field.getName()
					)
					.isNotEqualTo(field.get(defaults));
		}
	}

	private static Drifty.Ruleset maximal() {
		return Desired.ruleset()
				.withEnforcement(Drifty.RulesetEnforcement.EVALUATE)
				.withIncludePatterns(List.of("refs/heads/main"))
				.withExcludePatterns(List.of("refs/heads/tmp/*"))
				.withRequiredLinearHistory(true)
				.withNoForcePushes(true)
				.withStrictRequiredStatusChecks(true)
				.withRequiredStatusChecks(
						List.of(Desired.statusCheck("build", 15368L))
				)
				.withPullRequest(
						Desired.pullRequestRule()
								.withRequiredApprovingReviewCount(2L)
								.withDismissStaleReviewsOnPush(true)
								.withRequireCodeOwnerReview(true)
								.withRequireLastPushApproval(true)
								.withRequiredReviewThreadResolution(true)
								.withAllowedMergeMethods(
										List.of(Drifty.MergeMethod.SQUASH)
								)
				)
				.withRequiredCodeScanning(
						List.of(Desired.codeScanningTool("CodeQL"))
				)
				.withCreation(true)
				.withDeletion(true)
				.withRequiredSignatures(true)
				.withUpdate(true)
				.withUpdateAllowsFetchAndMerge(true)
				.withCommitMessagePattern(
						Desired.rulePattern(
								Drifty.PatternOperator.STARTS_WITH,
								"feat"
						)
				)
				.withCommitAuthorEmailPattern(
						Desired.rulePattern(
								Drifty.PatternOperator.ENDS_WITH,
								"@example.com"
						)
				)
				.withCommitterEmailPattern(
						Desired.rulePattern(
								Drifty.PatternOperator.CONTAINS,
								"noreply"
						)
				)
				.withBranchNamePattern(
						Desired.rulePattern(
								Drifty.PatternOperator.REGEX,
								"^feature/"
						)
				)
				.withTagNamePattern(
						Desired.rulePattern(
								Drifty.PatternOperator.STARTS_WITH,
								"v"
						)
				)
				.withRequiredDeployments(List.of("production"))
				.withMergeQueue(
						Desired.mergeQueueRule()
								.withCheckResponseTimeoutMinutes(30L)
								.withGroupingStrategy(
										Drifty.MergeQueueGroupingStrategy.HEADGREEN
								)
								.withMaxEntriesToBuild(4L)
								.withMaxEntriesToMerge(3L)
								.withMergeMethod(
										Drifty.MergeQueueMergeMethod.SQUASH
								)
								.withMinEntriesToMerge(2L)
								.withMinEntriesToMergeWaitMinutes(10L)
				)
				.withWorkflows(
						List.of(
								Desired.workflowRule(
										".github/workflows/ci.yml",
										7
								)
						)
				)
				.withFilePathRestrictions(List.of("secrets/*"))
				.withMaxFilePathLength(200L)
				.withFileExtensionRestrictions(List.of("*.exe"))
				.withMaxFileSize(50L)
				.withBypassActors(
						List.of(
								Desired.bypassActor(
										1L,
										Drifty.ActorType.TEAM,
										Drifty.BypassMode.ALWAYS
								)
						)
				);
	}

	/**
	 * The ruleset GitHub would return once it had applied the one write the fix
	 * sent — the request body, given the id and name the endpoint fills in,
	 * parsed as the response it shares a shape with.
	 */
	private static ActualRuleset written(RequestPatternBuilder pattern)
			throws Exception {
		List<LoggedRequest> requests = findAll(pattern);
		assertThat(requests).hasSize(1);
		ObjectNode body = (ObjectNode) MAPPER
				.readTree(requests.getFirst().getBodyAsString());
		body.put("id", 1);
		body.put("name", "rs");
		return ActualTypes.ruleset(
				MAPPER.treeToValue(body, RulesetDetailsResponse.class)
		);
	}

	private RulesetDriftGroup group(
			Drifty.Ruleset wanted,
			ActualRuleset actual
	) {
		return new RulesetDriftGroup(
				Map.of("rs", wanted),
				List.of(actual),
				client,
				new RepoRef("owner", "repo")
		);
	}

	private static List<String> paths(
			Drifty.Ruleset wanted,
			ActualRuleset actual
	) {
		return new RulesetDriftGroup(
				Map.of("rs", wanted),
				List.of(actual),
				null,
				new RepoRef("owner", "repo")
		).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.map(DriftItem::path)
				.toList();
	}

}
