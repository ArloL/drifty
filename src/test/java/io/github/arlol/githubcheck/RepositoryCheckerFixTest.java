package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftFixer;
import io.github.arlol.githubcheck.drift.DriftGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;
import io.github.arlol.githubcheck.drift.DriftItem;

@WireMockTest
class RepositoryCheckerFixTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	private static final String GOOD_DETAILS_JSON = """
			{
				"owner": {"login": "owner", "type": "Organization"},
				"description": "",
				"homepage": "",
				"has_issues": true,
				"has_projects": true,
				"has_wiki": true,
				"has_discussions": false,
				"is_template": false,
				"allow_forking": true,
				"web_commit_signoff_required": false,
				"default_branch": "main",
				"topics": [],
				"allow_merge_commit": true,
				"allow_squash_merge": true,
				"allow_rebase_merge": true,
				"allow_update_branch": false,
				"allow_auto_merge": false,
				"delete_branch_on_merge": false,
				"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
				"squash_merge_commit_message": "COMMIT_MESSAGES",
				"merge_commit_title": "MERGE_MESSAGE",
				"merge_commit_message": "PR_TITLE",
				"visibility": "public",
				"archived": false,
				"security_and_analysis": {
					"secret_scanning": {"status": "enabled"},
					"secret_scanning_push_protection": {"status": "enabled"}
				}
			}
			""";

	private static final String GOOD_BRANCH_PROTECTION_JSON = """
			{
				"enforce_admins": {"enabled": true},
				"required_linear_history": {"enabled": true},
				"allow_force_pushes": {"enabled": false},
				"required_status_checks": {
					"strict": false,
					"checks": []
				}
			}
			""";

	private static final String GOOD_WORKFLOW_PERMISSIONS_JSON = """
			{
				"default_workflow_permissions": "write",
				"can_approve_pull_request_reviews": true
			}
			""";

	private RepositoryChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		checker = new RepositoryChecker(client, true);
	}

	/**
	 * Runs the fixes and renders whatever stayed unfixed, which is what these
	 * tests assert on.
	 */
	private static List<String> unfixedMessages(
			RepositoryChecker checker,
			Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts
	) {
		return DriftFixer.applyFixes(groupDrifts)
				.unfixedItems()
				.stream()
				.map(DriftItem::message)
				.toList();
	}

	private Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		return checker.computeGroupDrifts(actual, desired);
	}

	// ─── Helpers
	// ──────────────────────────────────────────────────────────

	private RepositoryChecker checkerWithSecrets(
			WireMockRuntimeInfo wm,
			Map<String, String> secrets
	) {
		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		return new RepositoryChecker(client, true, secrets);
	}

	private static <T> T parse(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static ObjectNode merge(String baseJson, String overridesJson) {
		try {
			ObjectNode base = (ObjectNode) MAPPER.readTree(baseJson);
			ObjectNode overrides = (ObjectNode) MAPPER.readTree(overridesJson);
			base.setAll(overrides);
			return base;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static RepositoryDetailsResponse goodDetails() {
		return parse(GOOD_DETAILS_JSON, RepositoryDetailsResponse.class);
	}

	private static ActualWorkflowPermissions goodWorkflowPermissions() {
		return ActualTypes.workflowPermissions(
				parse(GOOD_WORKFLOW_PERMISSIONS_JSON, WorkflowPermissions.class)
		);
	}

	private static RepositoryState goodPublicState() {
		return new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);
	}

	private static RepositoryState stateWithDetailsOverride(
			String overridesJson
	) {
		String mergedDetails = merge(GOOD_DETAILS_JSON, overridesJson)
				.toString();
		var details = parse(mergedDetails, RepositoryDetailsResponse.class);
		return new RepositoryState(
				"repo",
				ActualTypes.repository(details),
				ActualTypes.securityAndAnalysis(details),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);
	}

	// ─── Tests
	// ──────────────────────────────────────────────────────────

	@Test
	void noDiffs_noApiCalls() throws Exception {
		var state = goodPublicState();
		var groupDrifts = computeGroupDrifts(
				state,
				Desired.repository("owner", "repo")
		);
		List<String> remaining = unfixedMessages(checker, groupDrifts);
		assertThat(remaining).isEmpty();
		verify(0, patchRequestedFor(urlEqualTo("/repos/owner/repo")));
		verify(0, putRequestedFor(urlEqualTo("/repos/owner/repo/topics")));
	}

	@Test
	void topicsDrift_putsTopics() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/topics"))
						.willReturn(okJson("{\"names\":[\"java\"]}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withTopics(List.of("java"));

		var state = goodPublicState(); // topics = []

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(urlEqualTo("/repos/owner/repo/topics"))
						.withRequestBody(equalToJson("{\"names\":[\"java\"]}"))
		);
	}

	@Test
	void descriptionDrift_patchesOnlyTheDriftedField() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct");

		var state = stateWithDetailsOverride("""
				{"description": "wrong"}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{"description": "correct"}
								"""))
		);
	}

	/**
	 * The bug this pins: {@code allow_forking} used to ride along in every
	 * org-owned repository's PATCH. An org with
	 * {@code members_can_fork_private_repositories} off answers that field with
	 * a 422 even when it already holds the wanted value, which failed the whole
	 * request over a setting that had not drifted.
	 */
	@Test
	void undriftedAllowForking_staysOutOfThePatch() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct")
				.withAllowForking(false);

		var state = stateWithDetailsOverride("""
				{"description": "wrong", "allow_forking": false}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		assertThat(unfixedMessages(checker, groupDrifts)).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{"description": "correct"}
								"""))
		);
	}

	/**
	 * GitHub applies what it accepts and rejects the rest, so a 422 on a
	 * multi-field PATCH says nothing about which field failed. A second pass
	 * sends each field on its own: the ones GitHub takes are fixed, and only
	 * the one it refuses is reported unfixed, with its own error as the reason.
	 */
	@Test
	void patchRejectingOneField_fixesTheRestAndNamesIt() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).atPriority(5)
						.willReturn(
								aResponse().withStatus(422)
										.withBody(
												"{\"message\": \"allow_forking is disabled for this organization\"}"
										)
						)
		);
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).atPriority(1)
						.withRequestBody(equalToJson("""
								{"description": "correct"}
								"""))
						.willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct")
				.withAllowForking(true);

		var state = stateWithDetailsOverride("""
				{"description": "wrong", "allow_forking": false}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		var unfixed = DriftFixer.applyFixes(groupDrifts).unfixed();

		assertThat(unfixed).hasSize(1);
		assertThat(unfixed.getFirst().item().path())
				.isEqualTo("repo_settings.allow_forking");
		assertThat(unfixed.getFirst().reason()).contains("422");

		verify(
				patchRequestedFor(
						urlEqualTo("/repos/owner/repo")
				).withRequestBody(equalToJson("""
						{"description": "correct", "allow_forking": true}
						"""))
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{"description": "correct"}
								"""))
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{"allow_forking": true}
								"""))
		);
		verify(3, patchRequestedFor(urlEqualTo("/repos/owner/repo")));
	}

	/**
	 * One drifted field is already its own attribution, so a failure needs no
	 * isolation pass — and must not cost a second request.
	 */
	@Test
	void singleFieldPatchFailure_isNotRetried() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(
						aResponse().withStatus(422)
								.withBody("{\"message\": \"nope\"}")
				)
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct");

		var state = stateWithDetailsOverride("""
				{"description": "wrong"}
				""");

		var unfixed = DriftFixer.applyFixes(computeGroupDrifts(state, desired))
				.unfixed();

		assertThat(unfixed).hasSize(1);
		assertThat(unfixed.getFirst().item().path())
				.isEqualTo("repo_settings.description");
		verify(1, patchRequestedFor(urlEqualTo("/repos/owner/repo")));
	}

	/**
	 * Visibility is deliberately check-only (see SPEC.md): public to private
	 * breaks forks, private to public exposes code. The PATCH therefore never
	 * carries it, and the report has to say so rather than claim a fix.
	 */
	@Test
	void visibilityDrift_isReportedUnfixed() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withVisibility(Drifty.Visibility.PRIVATE);

		var state = stateWithDetailsOverride("""
				{"visibility": "public"}
				""");

		var unfixed = DriftFixer.applyFixes(computeGroupDrifts(state, desired))
				.unfixed();

		assertThat(unfixed).hasSize(1);
		assertThat(unfixed.getFirst().item().path())
				.isEqualTo("repo_settings.visibility");
		verify(0, patchRequestedFor(urlEqualTo("/repos/owner/repo")));
	}

	@Test
	void allowRebaseMergeFalse_patchesWithConfigValue() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withAllowRebaseMerge(false);

		var state = stateWithDetailsOverride("""
				{"description": "wrong"}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{
									"description": "",
									"allow_rebase_merge": false
								}
								"""))
		);
	}

	@Test
	void multipleFieldsDrift_singlePatchCall() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct")
				.withHomepageUrl("https://example.com");

		var state = stateWithDetailsOverride("""
				{
					"description": "wrong",
					"homepage": "",
					"has_wiki": false,
					"allow_merge_commit": true
				}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{
									"description": "correct",
									"homepage": "https://example.com",
									"has_wiki": true
								}
								"""))
		);
	}

	@Test
	void unfixableDiffs_remainInList() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);
		stubFor(
				put(urlEqualTo("/repos/owner/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct");

		var state = stateWithDetailsOverride("""
				{
					"description": "wrong",
					"default_branch": "master"
				}
				""");
		// Also override vulnerability alerts to false
		var stateWithBadVuln = new RepositoryState(
				"repo",
				state.repository(),
				state.securityAndAnalysis(),
				false,
				false,
				false,
				false,
				false,
				state.branchProtections(),
				List.of(),
				state.actionSecrets(),
				Map.of(),
				state.environmentSecrets(),
				state.workflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(stateWithBadVuln, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/vulnerability-alerts")
				)
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(matching(".*default_branch.*main.*"))
		);
	}

	@Test
	void securitySettingsDrift_fixesAllSettings() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);
		stubFor(
				put(urlEqualTo("/repos/owner/repo/automated-security-fixes"))
						.willReturn(WireMock.noContent())
		);
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withAutomatedSecurityFixes(true);

		var baseState = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "disabled"},
								"secret_scanning_push_protection": {"status": "disabled"}
							}
						}
						"""
		);
		var state = new RepositoryState(
				"repo",
				baseState.repository(),
				baseState.securityAndAnalysis(),
				false,
				false,
				false,
				false,
				false,
				baseState.branchProtections(),
				List.of(),
				baseState.actionSecrets(),
				Map.of(),
				baseState.environmentSecrets(),
				baseState.workflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);
		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/vulnerability-alerts")
				)
		);
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/automated-security-fixes")
				)
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{
									"security_and_analysis": {
										"secret_scanning": {"status": "enabled"}
									}
								}
								"""))
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_push_protection": {"status": "enabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void partialSecurityDrift_fixesOnlyDrifted() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withAutomatedSecurityFixes(true);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				false,
				true,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/vulnerability-alerts")
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/automated-security-fixes")
				)
		);
		verify(0, patchRequestedFor(urlEqualTo("/repos/owner/repo")));
	}

	@Test
	void disableVulnerabilityAlerts_whenDesiredFalse() throws Exception {
		stubFor(
				delete(urlEqualTo("/repos/owner/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withVulnerabilityAlerts(false);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo("/repos/owner/repo/vulnerability-alerts")
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/vulnerability-alerts")
				)
		);
	}

	@Test
	void disableAutomatedSecurityFixes_whenDesiredFalse() throws Exception {
		stubFor(
				delete(urlEqualTo("/repos/owner/repo/automated-security-fixes"))
						.willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withAutomatedSecurityFixes(false);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				true,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo("/repos/owner/repo/automated-security-fixes")
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/automated-security-fixes")
				)
		);
	}

	@Test
	void disableSecretScanning_whenDesiredFalse() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanning(false)
				.withSecretScanningPushProtection(false);

		var state = goodPublicState();

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(
						urlEqualTo("/repos/owner/repo")
				).withRequestBody(equalToJson("""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "disabled"}
							}
						}
						"""))
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_push_protection": {"status": "disabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void partialSecretScanningDrift_onlyDriftedFieldPatched() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanningPushProtection(false);

		var state = goodPublicState();

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_push_protection": {"status": "disabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void secretScanningValidityChecksDrift_patches() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanningValidityChecks(true);

		var state = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "enabled"},
								"secret_scanning_push_protection": {"status": "enabled"},
								"secret_scanning_validity_checks": {"status": "disabled"}
							}
						}
						"""
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_validity_checks": {"status": "enabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void secretScanningNonProviderPatternsDrift_patches() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanningNonProviderPatterns(true);

		var state = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "enabled"},
								"secret_scanning_push_protection": {"status": "enabled"},
								"secret_scanning_non_provider_patterns": {"status": "disabled"}
							}
						}
						"""
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_non_provider_patterns": {"status": "enabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void advancedSecurityDrift_patches() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withAdvancedSecurity(true);

		var state = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "enabled"},
								"secret_scanning_push_protection": {"status": "enabled"},
								"advanced_security": {"status": "disabled"}
							}
						}
						"""
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(
						urlEqualTo("/repos/owner/repo")
				).withRequestBody(equalToJson("""
						{
							"security_and_analysis": {
								"advanced_security": {"status": "enabled"}
							}
						}
						"""))
		);
	}

	@Test
	void secretScanningAiDetectionDrift_patches() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanningAiDetection(true);

		var state = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "enabled"},
								"secret_scanning_push_protection": {"status": "enabled"},
								"secret_scanning_ai_detection": {"status": "disabled"}
							}
						}
						"""
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_ai_detection": {"status": "enabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void secretScanningDelegatedAlertDismissalDrift_patches() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanningDelegatedAlertDismissal(true);

		var state = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "enabled"},
								"secret_scanning_push_protection": {"status": "enabled"},
								"secret_scanning_delegated_alert_dismissal": {"status": "disabled"}
							}
						}
						"""
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_delegated_alert_dismissal": {"status": "enabled"}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void secretScanningDelegatedBypassDrift_patchesStatusAndReviewers()
			throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withSecretScanningDelegatedBypass(true)
				.withSecretScanningDelegatedBypassReviewers(
						List.of(
								Desired.bypassReviewer(
										7,
										Drifty.SecretScanningBypassReviewerType.TEAM
								)
						)
				);

		var state = stateWithDetailsOverride(
				"""
						{
							"security_and_analysis": {
								"secret_scanning": {"status": "enabled"},
								"secret_scanning_push_protection": {"status": "enabled"},
								"secret_scanning_delegated_bypass": {"status": "disabled"}
							}
						}
						"""
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning_delegated_bypass": {"status": "enabled"},
														"secret_scanning_delegated_bypass_options": {
															"reviewers": [
																{"reviewer_id": 7, "reviewer_type": "TEAM"}
															]
														}
													}
												}
												"""
								)
						)
		);
	}

	@Test
	void enablePrivateVulnerabilityReporting_whenDesiredTrue()
			throws Exception {
		stubFor(
				put(
						urlEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				).willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withPrivateVulnerabilityReporting(true);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				)
		);
		verify(
				0,
				deleteRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				)
		);
	}

	@Test
	void disablePrivateVulnerabilityReporting_whenDesiredFalse()
			throws Exception {
		stubFor(
				delete(
						urlEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				).willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withPrivateVulnerabilityReporting(false);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				true,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				)
		);
	}

	@Test
	void enableCodeScanningDefaultSetup_whenDesiredTrue() throws Exception {
		stubFor(
				patch(
						urlEqualTo(
								"/repos/owner/repo/code-scanning/default-setup"
						)
				).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withCodeScanningDefaultSetup(true);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/code-scanning/default-setup"
						)
				).withRequestBody(equalToJson("{\"state\": \"configured\"}"))
		);
	}

	@Test
	void disableCodeScanningDefaultSetup_whenDesiredFalse() throws Exception {
		stubFor(
				patch(
						urlEqualTo(
								"/repos/owner/repo/code-scanning/default-setup"
						)
				).willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withCodeScanningDefaultSetup(false);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				true,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/code-scanning/default-setup"
						)
				).withRequestBody(
						equalToJson("{\"state\": \"not-configured\"}")
				)
		);
	}

	@Test
	void workflowPermissionsDrift_putsWorkflowPermissions() throws Exception {
		stubFor(
				put(
						urlEqualTo(
								"/repos/owner/repo/actions/permissions/workflow"
						)
				).willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo");

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				new ActualWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
						false
				),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/actions/permissions/workflow"
						)
				).withRequestBody(equalToJson("""
						{
							"default_workflow_permissions": "write",
							"can_approve_pull_request_reviews": true
						}
						"""))
		);
	}

	@Test
	void noWorkflowPermissionsDrift_noPutCall() throws Exception {
		Drifty.Repository desired = Desired.repository("owner", "repo");
		var state = goodPublicState();

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				0,
				putRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/actions/permissions/workflow"
						)
				)
		);
	}

	@Test
	void branchProtectionMissing_putsBranchProtection() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withEnforceAdmins(true)
										.withRequiredLinearHistory(true)
						)
				);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/branches/main/protection")
				).withRequestBody(equalToJson("""
						{
							"required_status_checks": {
								"strict": false,
								"checks": []
							},
							"enforce_admins": true,
							"required_pull_request_reviews": null,
							"restrictions": null,
							"required_linear_history": true,
							"allow_force_pushes": false
						}
						"""))
		);
	}

	@Test
	void immutableReleasesDisabled_enablesThem() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/immutable-releases"))
						.willReturn(WireMock.noContent())
		);

		var desired = Desired.repository("owner", "repo")
				.withImmutableReleases(true);

		var state = goodPublicState();

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/immutable-releases")
				)
		);
	}

	@Test
	void immutableReleasesEnabled_disablesThem() throws Exception {
		stubFor(
				WireMock.delete(
						urlEqualTo("/repos/owner/repo/immutable-releases")
				).willReturn(WireMock.noContent())
		);

		var desired = Desired.repository("owner", "repo");

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				true,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				WireMock.deleteRequestedFor(
						urlEqualTo("/repos/owner/repo/immutable-releases")
				)
		);
	}

	@Test
	void branchProtectionDrift_putsBranchProtection() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withEnforceAdmins(true)
										.withRequiredLinearHistory(true)
						)
				);

		var driftedBp = parse(
				"""
						{
							"enforce_admins": {"enabled": false},
							"required_linear_history": {"enabled": true},
							"allow_force_pushes": {"enabled": false},
							"required_status_checks": {
								"strict": false,
								"checks": [
									{"context": "check-actions.required-status-check"},
									{"context": "codeql-analysis.required-status-check"},
									{"context": "CodeQL"},
									{"context": "zizmor"}
								]
							}
						}
						""",
				BranchProtectionResponse.class
		);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of("main", ActualTypes.branchProtection(driftedBp)),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/branches/main/protection")
				).withRequestBody(equalToJson("""
						{
							"required_status_checks": {
								"strict": false,
								"checks": []
							},
							"enforce_admins": true,
							"required_pull_request_reviews": null,
							"restrictions": null,
							"required_linear_history": true,
							"allow_force_pushes": false
						}
						"""))
		);
	}

	@Test
	void branchProtectionWithPRReviews_putsBranchProtection() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		var bp = Desired.branchProtection()
				.withEnforceAdmins(true)
				.withRequiredLinearHistory(true)
				.withRequiredApprovingReviewCount(1L)
				.withDismissStaleReviews(true)
				.withRequireCodeOwnerReviews(true);
		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withBranchProtections(Map.of("main", bp));

		var driftedBp = parse("""
				{
					"enforce_admins": {"enabled": false},
					"required_linear_history": {"enabled": true},
					"allow_force_pushes": {"enabled": false},
					"required_status_checks": {
						"strict": false,
						"checks": []
					}
				}
				""", BranchProtectionResponse.class);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of("main", ActualTypes.branchProtection(driftedBp)),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/branches/main/protection")
				).withRequestBody(equalToJson("""
						{
							"required_status_checks": {
								"strict": false,
								"checks": []
							},
							"enforce_admins": true,
							"required_pull_request_reviews": {
								"dismiss_stale_reviews": true,
								"require_code_owner_reviews": true,
								"required_approving_review_count": 1,
								"require_last_push_approval": null
							},
							"restrictions": null,
							"required_linear_history": true,
							"allow_force_pushes": false
						}
						"""))
		);
	}

	@Test
	void branchProtectionWithRestrictions_putsBranchProtection()
			throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		var bp = Desired.branchProtection()
				.withEnforceAdmins(true)
				.withRequiredLinearHistory(true)
				.withUsers(List.of("admin-user", "dev-user"))
				.withTeams(List.of("admins"))
				.withApps(List.of("my-app"));
		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withBranchProtections(Map.of("main", bp));

		var driftedBp = parse("""
				{
					"enforce_admins": {"enabled": false},
					"required_linear_history": {"enabled": true},
					"allow_force_pushes": {"enabled": false},
					"required_status_checks": {
						"strict": false,
						"checks": []
					}
				}
				""", BranchProtectionResponse.class);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of("main", ActualTypes.branchProtection(driftedBp)),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/branches/main/protection")
				).withRequestBody(equalToJson("""
						{
							"required_status_checks": {
								"strict": false,
								"checks": []
							},
							"enforce_admins": true,
							"required_pull_request_reviews": null,
							"restrictions": {
								"users": ["admin-user", "dev-user"],
								"teams": ["admins"],
								"apps": ["my-app"]
							},
							"required_linear_history": true,
							"allow_force_pushes": false
						}
						"""))
		);
	}

	@Test
	void noBranchProtectionDrift_noPutCall() throws Exception {
		Drifty.Repository desired = Desired.repository("owner", "repo");
		var state = goodPublicState();

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/branches/main/protection")
				)
		);
	}

	@Test
	void extraBranchProtection_deletesProtection() throws Exception {
		stubFor(
				delete(urlEqualTo("/repos/owner/repo/branches/main/protection"))
						.willReturn(WireMock.noContent())
		);

		Drifty.Repository desired = Desired.repository("owner", "repo");

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(
						"main",
						ActualTypes.branchProtection(
								parse(
										GOOD_BRANCH_PROTECTION_JSON,
										BranchProtectionResponse.class
								)
						)
				),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo("/repos/owner/repo/branches/main/protection")
				)
		);
	}

	@Test
	void repoFieldsAndTopics_bothFixed() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);
		stubFor(
				put(urlEqualTo("/repos/owner/repo/topics"))
						.willReturn(okJson("{\"names\":[\"java\"]}"))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withDescription("correct")
				.withTopics(List.of("java"));

		var state = stateWithDetailsOverride("""
				{"description": "wrong"}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("""
								{"description": "correct"}
								"""))
		);
		verify(
				putRequestedFor(urlEqualTo("/repos/owner/repo/topics"))
						.withRequestBody(equalToJson("{\"names\":[\"java\"]}"))
		);
	}

	// ─── Ruleset tests
	// ──────────────────────────────────────────────────────

	@Test
	void rulesetMissing_postsToCreateRuleset() throws Exception {
		stubFor(
				post(urlEqualTo("/repos/owner/repo/rulesets")).willReturn(
						WireMock.status(201).withBody("{\"id\": 1}")
				)
		);

		var desired = Desired.repository("owner", "repo")
				.withRulesets(
						Map.of(
								"main-branch-rules",
								Desired.ruleset()
										.withIncludePatterns(
												List.of("~DEFAULT_BRANCH")
										)
										.withRequiredLinearHistory(true)
										.withNoForcePushes(true)
										.withRequiredStatusChecks(
												List.of(
														Desired.statusCheck(
																"CodeQL"
														),
														Desired.statusCheck(
																"zizmor"
														)
												)
										)
						)
				);

		var state = goodPublicState(); // no rulesets

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				postRequestedFor(urlEqualTo("/repos/owner/repo/rulesets"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"name": "main-branch-rules",
													"target": "branch",
													"enforcement": "active",
													"conditions": {
														"ref_name": {
															"include": ["~DEFAULT_BRANCH"],
															"exclude": []
														}
													},
													"rules": [
														{"type": "required_linear_history"},
														{"type": "non_fast_forward"},
														{
															"type": "required_status_checks",
															"parameters": {
																"required_status_checks": [
																	{"context": "CodeQL"},
																	{"context": "zizmor"}
																],
																"strict_required_status_checks_policy": false
															}
														}
													]
												}
												""",
										true,
										false
								)
						)
		);
	}

	@Test
	void rulesetDrift_putsToUpdateRuleset() throws Exception {
		stubFor(
				put(urlMatching("/repos/owner/repo/rulesets/42"))
						.willReturn(okJson("{\"id\": 42}"))
		);

		var desired = Desired.repository("owner", "repo")
				.withRulesets(
						Map.of(
								"main-branch-rules",
								Desired.ruleset()
										.withIncludePatterns(
												List.of("~DEFAULT_BRANCH")
										)
										.withRequiredLinearHistory(true)
										.withNoForcePushes(false)
						)
				);

		var include = List.of("~DEFAULT_BRANCH");
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						include,
						List.of()
				),
				null,
				null,
				null
		);
		// Actual ruleset is missing required_linear_history — drift
		var actualRuleset = new RulesetDetailsResponse(
				42L,
				"main-branch-rules",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				conditions,
				List.of()
		);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(ActualTypes.ruleset(actualRuleset)),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(putRequestedFor(urlEqualTo("/repos/owner/repo/rulesets/42")));
	}

	@Test
	void noRulesetDrift_noApiCalls() throws Exception {
		var include = List.of("~DEFAULT_BRANCH");
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						include,
						List.of()
				),
				null,
				null,
				null
		);
		var actualRuleset = new RulesetDetailsResponse(
				1L,
				"main-branch-rules",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				conditions,
				List.of(
						new Rule.RequiredLinearHistory(),
						new Rule.RequiredStatusChecks(
								new Rule.RequiredStatusChecks.Parameters(
										List.of(
												new Rule.StatusCheck(
														"CodeQL",
														null
												)
										),
										false
								)
						)
				)
		);

		var desired = Desired.repository("owner", "repo")
				.withRulesets(
						Map.of(
								"main-branch-rules",
								Desired.ruleset()
										.withIncludePatterns(
												List.of("~DEFAULT_BRANCH")
										)
										.withRequiredLinearHistory(true)
										.withRequiredStatusChecks(
												List.of(
														Desired.statusCheck(
																"CodeQL"
														)
												)
										)
						)
				);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(ActualTypes.ruleset(actualRuleset)),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(0, postRequestedFor(urlEqualTo("/repos/owner/repo/rulesets")));
		verify(
				0,
				putRequestedFor(urlMatching("/repos/owner/repo/rulesets/.*"))
		);
	}

	@Test
	void rulesetCodeScanning_createsRuleset() throws Exception {
		stubFor(
				post(urlEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(WireMock.status(201).withBody("""
								{
									"id": 1,
									"name": "main-branch-rules",
									"target": "branch",
									"enforcement": "active"
								}
								"""))
		);

		var desired = Desired.repository("owner", "repo")
				.withRulesets(
						Map.of(
								"main-branch-rules",
								Desired.ruleset()
										.withIncludePatterns(
												List.of("~DEFAULT_BRANCH")
										)
										.withRequiredCodeScanning(
												List.of(
														Desired.codeScanningTool(
																"CodeQL"
														)
												)
										)
						)
				);

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				postRequestedFor(urlEqualTo("/repos/owner/repo/rulesets"))
						.withRequestBody(
								containing("\"type\":\"code_scanning\"")
						)
		);
	}

	// ─── Pages tests
	// ──────────────────────────────────────────────────────

	@Test
	void pagesMissing_postsToCreate() throws Exception {
		stubFor(
				post(urlEqualTo("/repos/owner/repo/pages"))
						.willReturn(WireMock.status(201).withBody("""
								{
									"build_type": "workflow",
									"https_enforced": true,
									"public": true,
									"custom_404": false
								}
								"""))
		);

		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.pages());

		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of("github-pages", List.of()),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				postRequestedFor(urlEqualTo("/repos/owner/repo/pages"))
						.withRequestBody(equalToJson("""
								{"build_type": "workflow"}
								"""))
		);
	}

	@Test
	void pagesDrift_putsToUpdate() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/pages"))
						.willReturn(WireMock.noContent())
		);

		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.pages());

		var actualPages = new ActualPages(
				"workflow",
				Optional.empty(),
				false // https_enforced is false → drift
		);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of("github-pages", List.of()),
				goodWorkflowPermissions(),
				Optional.of(actualPages)
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(urlEqualTo("/repos/owner/repo/pages"))
						.withRequestBody(equalToJson("""
								{
									"build_type": "workflow",
									"https_enforced": true
								}
								"""))
		);
	}

	@Test
	void noPagesDesired_noPagesApiCall() throws Exception {
		var desired = Desired.repository("owner", "repo");

		var state = goodPublicState();

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(0, postRequestedFor(urlEqualTo("/repos/owner/repo/pages")));
		verify(0, putRequestedFor(urlEqualTo("/repos/owner/repo/pages")));
	}

	// ─── Environment config fix tests
	// ──────────────────────────────────────

	@Test
	void environmentWaitTimerDrift_putsEnvironmentUpdate() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/environments/production"))
						.willReturn(okJson("{}"))
		);

		var desired = Desired.repository("owner", "repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment().withWaitTimer(30)
						)
				);

		var actualEnv = new ActualEnvironment(10, false, false);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of("production", actualEnv),
				Map.of("production", List.of()),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/environments/production")
				).withRequestBody(equalToJson("""
						{"wait_timer": 30}
						"""))
		);
	}

	@Test
	void environmentDeploymentBranchPolicyDrift_putsEnvironmentUpdate()
			throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/environments/production"))
						.willReturn(okJson("{}"))
		);

		var desired = Desired.repository("owner", "repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withProtectedBranches(true)
										.withCustomBranchPolicies(false)
						)
				);

		var actualEnv = new ActualEnvironment(0, false, true);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of("production", actualEnv),
				Map.of("production", List.of()),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/environments/production")
				).withRequestBody(equalToJson("""
						{
							"deployment_branch_policy": {
								"protected_branches": true,
								"custom_branch_policies": false
							}
						}
						"""))
		);
	}

	@Test
	void noEnvironmentConfigDrift_noEnvironmentApiCall() throws Exception {
		var desired = Desired.repository("owner", "repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment().withWaitTimer(30)
						)
				);

		var actualEnv = new ActualEnvironment(30, false, false);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of("production", actualEnv),
				Map.of("production", List.of()),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/environments/production")
				)
		);
	}

	// ─── Secret creation via --fix
	// ──────────────────────────────────────────

	// 32 zero bytes base64-encoded — a valid-length curve25519 public key
	private static final String TEST_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	@Test
	void missingActionSecret_withValueInMap_createsSecret(
			WireMockRuntimeInfo wm
	) throws Exception {
		stubFor(
				WireMock.get(
						urlEqualTo(
								"/repos/owner/repo/actions/secrets/public-key"
						)
				).willReturn(okJson("""
						{"key_id": "123", "key": "%s"}
						""".formatted(TEST_PUBLIC_KEY)))
		);
		stubFor(
				put(urlEqualTo("/repos/owner/repo/actions/secrets/PAT"))
						.willReturn(WireMock.status(201))
		);
		stubFor(
				WireMock.get(
						urlEqualTo("/repos/owner/repo/actions/secrets/PAT")
				).willReturn(okJson("""
						{
							"name": "PAT",
							"created_at": "2024-01-01T00:00:00Z",
							"updated_at": "2024-06-01T00:00:00Z"
						}
						"""))
		);

		var localChecker = checkerWithSecrets(
				wm,
				Map.of("repo-PAT", "ghp_test_value")
		);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);
		var desired = Desired.repository("owner", "repo")
				.withActionsSecrets(List.of("PAT"));

		var groupDrifts = localChecker.computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(localChecker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				putRequestedFor(
						urlEqualTo("/repos/owner/repo/actions/secrets/PAT")
				)
		);
	}

	@Test
	void missingActionSecret_withoutValueInMap_remainsUnfixed(
			WireMockRuntimeInfo wm
	) throws Exception {
		var localChecker = checkerWithSecrets(wm, Map.of());
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);
		var desired = Desired.repository("owner", "repo")
				.withActionsSecrets(List.of("PAT"));

		var groupDrifts = localChecker.computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(localChecker, groupDrifts);

		assertThat(remaining).anyMatch(
				d -> d.contains("action_secrets") && d.contains("missing")
						&& d.contains("PAT")
		);
		verify(
				0,
				putRequestedFor(
						urlMatching("/repos/owner/repo/actions/secrets/.*")
				)
		);
	}

	@Test
	void missingEnvironmentSecret_withValueInMap_createsSecret(
			WireMockRuntimeInfo wm
	) throws Exception {
		stubFor(
				WireMock.get(
						urlEqualTo(
								"/repos/owner/repo/environments/production/secrets/public-key"
						)
				).willReturn(okJson("""
						{"key_id": "456", "key": "%s"}
						""".formatted(TEST_PUBLIC_KEY)))
		);
		stubFor(
				put(
						urlEqualTo(
								"/repos/owner/repo/environments/production/secrets/TF_GITHUB_TOKEN"
						)
				).willReturn(WireMock.status(201))
		);
		stubFor(
				WireMock.get(
						urlEqualTo(
								"/repos/owner/repo/environments/production/secrets/TF_GITHUB_TOKEN"
						)
				).willReturn(okJson("""
						{
							"name": "TF_GITHUB_TOKEN",
							"created_at": "2024-01-01T00:00:00Z",
							"updated_at": "2024-06-01T00:00:00Z"
						}
						"""))
		);

		var localChecker = checkerWithSecrets(
				wm,
				Map.of("repo-production-TF_GITHUB_TOKEN", "ghp_test_value")
		);
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				true,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of("production", new ActualEnvironment(0, false, false)),
				Map.of("production", List.of()),
				goodWorkflowPermissions(),
				Optional.empty()
		);
		var desired = Desired.repository("owner", "repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("TF_GITHUB_TOKEN"))
						)
				);

		var groupDrifts = localChecker.computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(localChecker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				putRequestedFor(
						urlEqualTo(
								"/repos/owner/repo/environments/production/secrets/TF_GITHUB_TOKEN"
						)
				)
		);
	}

	// ─── Archived tests
	// ──────────────────────────────────────────────────────

	@Test
	void archiveDrift_patches_archivedTrue() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		var desired = Desired.repository("owner", "repo").withArchived(true);
		var state = goodPublicState(); // not archived

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("{\"archived\": true}"))
		);
	}

	@Test
	void unarchiveDrift_patches_archivedFalse() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		var desired = Desired.repository("owner", "repo"); // not archived
		var state = stateWithDetailsOverride("""
				{"archived": true}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(urlEqualTo("/repos/owner/repo"))
						.withRequestBody(equalToJson("{\"archived\": false}"))
		);
	}

	/**
	 * Every other fix fails against an archived repository, so unarchiving has
	 * to happen first. That must be a property of the fix run, not of the order
	 * the drift groups happen to be iterated in — so this hands applyFixes a
	 * map that deliberately puts the archive group last.
	 */
	@Test
	void unarchiveRunsBeforeOtherFixesWhateverTheIterationOrder()
			throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/owner/repo")).willReturn(okJson("{}"))
		);

		// Wants the repo active and the description changed, so both the
		// archive group and the repo-settings group have work to do.
		var desired = Desired.repository("owner", "repo")
				.withDescription("a new description");
		var state = stateWithDetailsOverride("""
				{"archived": true}
				""");

		var groupDrifts = computeGroupDrifts(state, desired);
		assertThat(groupDrifts.keySet().stream().map(DriftGroup::name)).as(
				"both groups must have drifted for this test to mean anything"
		).contains(Drifty.GroupName.ARCHIVED, Drifty.GroupName.REPO_SETTINGS);

		DriftFixer.applyFixes(reversed(groupDrifts));

		var patches = WireMock
				.findAll(patchRequestedFor(urlEqualTo("/repos/owner/repo")));
		assertThat(patches).hasSizeGreaterThanOrEqualTo(2);
		assertThat(patches.getFirst().getBodyAsString())
				.as("unarchiving must be the first write")
				.contains("\"archived\":false");
	}

	private static Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> reversed(
			Map<DriftGroup<Drifty.GroupName>, List<DriftFix>> groupDrifts
	) {
		var entries = new ArrayList<>(groupDrifts.entrySet());
		Collections.reverse(entries);
		var reversed = new LinkedHashMap<DriftGroup<Drifty.GroupName>, List<DriftFix>>();
		entries.forEach(e -> reversed.put(e.getKey(), e.getValue()));
		return reversed;
	}

	/**
	 * Two independent settings drift the same way, so they render identical
	 * messages. One fix succeeds and the other fails; the failed one must stay
	 * in the report. Subtracting fixed items by rendered string lets the
	 * successful fix erase the failed one's drift.
	 */
	@Test
	void failedFixIsNotErasedBySuccessfulFixOfAnotherSetting()
			throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/vulnerability-alerts"))
						.willReturn(WireMock.aResponse().withStatus(204))
		);
		stubFor(
				put(urlEqualTo("/repos/owner/repo/immutable-releases"))
						.willReturn(WireMock.aResponse().withStatus(500))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withVulnerabilityAlerts(true)
				.withImmutableReleases(true);

		// Both flags are off on GitHub, so both groups detect drift.
		var state = new RepositoryState(
				"repo",
				ActualTypes.repository(goodDetails()),
				ActualTypes.securityAndAnalysis(goodDetails()),
				false,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				goodWorkflowPermissions(),
				Optional.empty()
		);

		var groupDrifts = computeGroupDrifts(state, desired);

		var remaining = unfixedMessages(checker, groupDrifts);

		assertThat(remaining).as(
				"the immutable-releases fix returned 500, so its drift must survive"
		).hasSize(1);
		assertThat(remaining.getFirst()).contains("immutable_releases");
	}

	/**
	 * SPEC.md promises "FAILED with reason" per setting. A fix that throws must
	 * therefore surface why, not just leave the drift unexplained.
	 */
	@Test
	void apiFailureIsReportedWithItsReason() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/immutable-releases"))
						.willReturn(WireMock.aResponse().withStatus(500))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withImmutableReleases(true);

		var groupDrifts = computeGroupDrifts(goodPublicState(), desired);
		var outcome = DriftFixer.applyFixes(groupDrifts);

		assertThat(outcome.fixed()).isEmpty();
		assertThat(outcome.unfixed()).singleElement().satisfies(unfixed -> {
			assertThat(unfixed.item().path())
					.isEqualTo("immutable_releases.enabled");
			assertThat(unfixed.reason()).contains("500");
		});
	}

	/**
	 * A secret with no value in DRIFTY_GITHUB_SECRETS is unfixable rather than
	 * failed, and must say so.
	 */
	@Test
	void unfixableSecretIsReportedWithItsReason(WireMockRuntimeInfo wm)
			throws Exception {
		var localChecker = checkerWithSecrets(wm, Map.of());

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withActionsSecrets(List.of("PAT"));

		var groupDrifts = localChecker
				.computeGroupDrifts(goodPublicState(), desired);
		var outcome = DriftFixer.applyFixes(groupDrifts);

		assertThat(outcome.unfixed()).singleElement().satisfies(unfixed -> {
			assertThat(unfixed.item().path()).isEqualTo("action_secrets.PAT");
			assertThat(unfixed.reason()).contains("DRIFTY_GITHUB_SECRETS")
					.contains("repo-PAT");
		});
	}

	/**
	 * A successful fix reports the items it resolved, so the report can print
	 * FIXED per setting rather than merely omitting them.
	 */
	@Test
	void successfulFixReportsWhatItFixed() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/owner/repo/immutable-releases"))
						.willReturn(WireMock.aResponse().withStatus(204))
		);

		Drifty.Repository desired = Desired.repository("owner", "repo")
				.withImmutableReleases(true);

		var groupDrifts = computeGroupDrifts(goodPublicState(), desired);
		var outcome = DriftFixer.applyFixes(groupDrifts);

		assertThat(outcome.unfixed()).isEmpty();
		assertThat(outcome.fixed().stream().map(DriftItem::path))
				.containsExactly("immutable_releases.enabled");
	}

}
