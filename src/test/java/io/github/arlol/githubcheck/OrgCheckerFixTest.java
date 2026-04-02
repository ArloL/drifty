package io.github.arlol.githubcheck;

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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryFull;
import io.github.arlol.githubcheck.client.RepositoryMinimal;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.config.BranchProtectionArgs;
import io.github.arlol.githubcheck.config.CodeScanningToolArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

@WireMockTest
class OrgCheckerFixTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	private static final String GOOD_SUMMARY_JSON = """
			{
				"name": "repo",
				"archived": false,
				"visibility": "public"
			}
			""";

	private static final String GOOD_DETAILS_JSON = """
			{
				"description": "",
				"homepage": "",
				"has_issues": true,
				"has_projects": true,
				"has_wiki": true,
				"default_branch": "main",
				"topics": [],
				"allow_merge_commit": true,
				"allow_squash_merge": true,
				"allow_rebase_merge": true,
				"allow_update_branch": false,
				"allow_auto_merge": false,
				"delete_branch_on_merge": false,
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

	private static final String FULL_DESIRED_REPO_SETTINGS = """
			{
				"archived": false,
				"description": "",
				"homepage": "",
				"has_issues": true,
				"has_projects": true,
				"has_wiki": true,
				"allow_merge_commit": true,
				"allow_squash_merge": true,
				"allow_rebase_merge": true,
				"allow_update_branch": false,
				"allow_auto_merge": false,
				"delete_branch_on_merge": false
			}
			""";

	private OrgChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		checker = new OrgChecker(client, "ArloL", true);
	}

	// ─── Helpers
	// ──────────────────────────────────────────────────────────

	private OrgChecker checkerWithSecrets(
			WireMockRuntimeInfo wm,
			Map<String, String> secrets
	) {
		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		return new OrgChecker(client, "ArloL", true, secrets);
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

	private static RepositoryState goodPublicState() {
		return new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);
	}

	private static RepositoryState stateWithDetailsOverride(
			String overridesJson
	) {
		String mergedDetails = merge(GOOD_DETAILS_JSON, overridesJson)
				.toString();
		return new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(mergedDetails, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);
	}

	// ─── Tests
	// ──────────────────────────────────────────────────────────

	@Test
	void noDiffs_noApiCalls() throws Exception {
		var state = goodPublicState();
		List<String> remaining = checker.applyFixes(
				"repo",
				state,
				RepositoryArgs.create("repo").build(),
				List.of()
		);
		assertThat(remaining).isEmpty();
		verify(0, patchRequestedFor(urlEqualTo("/repos/ArloL/repo")));
		verify(0, putRequestedFor(urlEqualTo("/repos/ArloL/repo/topics")));
	}

	@Test
	void descriptionDrift_patchesFullDesiredState() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.description("correct")
				.build();

		var state = stateWithDetailsOverride("""
				{"description": "wrong"}
				""");

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(equalToJson("""
								{
									"archived": false,
									"description": "correct",
									"homepage": "",
									"has_issues": true,
									"has_projects": true,
									"has_wiki": true,
									"allow_merge_commit": true,
									"allow_squash_merge": true,
									"allow_rebase_merge": true,
									"allow_update_branch": false,
									"allow_auto_merge": false,
									"delete_branch_on_merge": false,
									"default_branch": "main"
								}
								"""))
		);
	}

	@Test
	void allowRebaseMergeFalse_patchesWithConfigValue() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.allowRebaseMerge(false)
				.build();

		var state = stateWithDetailsOverride("""
				{"allow_rebase_merge": true}
				""");

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(equalToJson("""
								{
									"archived": false,
									"description": "",
									"homepage": "",
									"has_issues": true,
									"has_projects": true,
									"has_wiki": true,
									"allow_merge_commit": true,
									"allow_squash_merge": true,
									"allow_rebase_merge": false,
									"allow_update_branch": false,
									"allow_auto_merge": false,
									"delete_branch_on_merge": false,
									"default_branch": "main"
								}
								"""))
		);
	}

	@Test
	void multipleFieldsDrift_singlePatchCall() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.description("correct")
				.homepageUrl("https://example.com")
				.build();

		var state = stateWithDetailsOverride("""
				{
					"description": "wrong",
					"homepage": "",
					"has_wiki": false,
					"allow_merge_commit": true
				}
				""");

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(equalToJson("""
								{
									"archived": false,
									"description": "correct",
									"homepage": "https://example.com",
									"has_issues": true,
									"has_projects": true,
									"has_wiki": true,
									"allow_merge_commit": true,
									"allow_squash_merge": true,
									"allow_rebase_merge": true,
									"allow_update_branch": false,
									"allow_auto_merge": false,
									"delete_branch_on_merge": false,
									"default_branch": "main"
								}
								"""))
		);
	}

	@Test
	void topicsDrift_putsTopics() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/topics"))
						.willReturn(okJson("{\"names\":[\"java\"]}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.topics("java")
				.build();

		var state = goodPublicState(); // topics = []

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(urlEqualTo("/repos/ArloL/repo/topics"))
						.withRequestBody(equalToJson("{\"names\":[\"java\"]}"))
		);
	}

	@Test
	void unfixableDiffs_remainInList() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.description("correct")
				.build();

		var state = stateWithDetailsOverride("""
				{
					"description": "wrong",
					"default_branch": "master"
				}
				""");
		// Also override vulnerability alerts to false
		var stateWithBadVuln = new RepositoryState(
				"repo",
				state.summary(),
				state.details(),
				false,
				false,
				state.branchProtections(),
				state.actionSecretNames(),
				state.environmentSecretNames(),
				state.workflowPermissions(),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(stateWithBadVuln, desired);
		List<String> remaining = checker
				.applyFixes("repo", stateWithBadVuln, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/vulnerability-alerts")
				)
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(matching(".*default_branch.*main.*"))
		);
	}

	@Test
	void securitySettingsDrift_fixesAllSettings() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/automated-security-fixes"))
						.willReturn(WireMock.noContent())
		);
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.automatedSecurityFixes(true)
				.build();

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
				baseState.summary(),
				baseState.details(),
				false,
				false,
				baseState.branchProtections(),
				baseState.actionSecretNames(),
				baseState.environmentSecretNames(),
				baseState.workflowPermissions(),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/vulnerability-alerts")
				)
		);
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/automated-security-fixes")
				)
		);
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning": {"status": "enabled"},
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
				put(urlEqualTo("/repos/ArloL/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.automatedSecurityFixes(true)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				false,
				true,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/vulnerability-alerts")
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/automated-security-fixes")
				)
		);
		verify(0, patchRequestedFor(urlEqualTo("/repos/ArloL/repo")));
	}

	@Test
	void disableVulnerabilityAlerts_whenDesiredFalse() throws Exception {
		stubFor(
				delete(urlEqualTo("/repos/ArloL/repo/vulnerability-alerts"))
						.willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.vulnerabilityAlerts(false)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo("/repos/ArloL/repo/vulnerability-alerts")
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/vulnerability-alerts")
				)
		);
	}

	@Test
	void disableAutomatedSecurityFixes_whenDesiredFalse() throws Exception {
		stubFor(
				delete(urlEqualTo("/repos/ArloL/repo/automated-security-fixes"))
						.willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.automatedSecurityFixes(false)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				true,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo("/repos/ArloL/repo/automated-security-fixes")
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/automated-security-fixes")
				)
		);
	}

	@Test
	void disableSecretScanning_whenDesiredFalse() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.secretScanning(false)
				.secretScanningPushProtection(false)
				.build();

		var state = goodPublicState();

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(
								equalToJson(
										"""
												{
													"security_and_analysis": {
														"secret_scanning": {"status": "disabled"},
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
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.secretScanningPushProtection(false)
				.build();

		var state = goodPublicState();

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
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
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.secretScanningValidityChecks(true)
				.build();

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

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
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
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.secretScanningNonProviderPatterns(true)
				.build();

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

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
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
	void enablePrivateVulnerabilityReporting_whenDesiredTrue()
			throws Exception {
		stubFor(
				put(
						urlEqualTo(
								"/repos/ArloL/repo/private-vulnerability-reporting"
						)
				).willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.privateVulnerabilityReporting(true)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/private-vulnerability-reporting"
						)
				)
		);
		verify(
				0,
				deleteRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/private-vulnerability-reporting"
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
								"/repos/ArloL/repo/private-vulnerability-reporting"
						)
				).willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.privateVulnerabilityReporting(false)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				true,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				deleteRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/private-vulnerability-reporting"
						)
				)
		);
		verify(
				0,
				putRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/private-vulnerability-reporting"
						)
				)
		);
	}

	@Test
	void enableCodeScanningDefaultSetup_whenDesiredTrue() throws Exception {
		stubFor(
				patch(
						urlEqualTo(
								"/repos/ArloL/repo/code-scanning/default-setup"
						)
				).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.codeScanningDefaultSetup(true)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/code-scanning/default-setup"
						)
				).withRequestBody(equalToJson("{\"state\": \"configured\"}"))
		);
	}

	@Test
	void disableCodeScanningDefaultSetup_whenDesiredFalse() throws Exception {
		stubFor(
				patch(
						urlEqualTo(
								"/repos/ArloL/repo/code-scanning/default-setup"
						)
				).willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.codeScanningDefaultSetup(false)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				true
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				1,
				patchRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/code-scanning/default-setup"
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
								"/repos/ArloL/repo/actions/permissions/workflow"
						)
				).willReturn(WireMock.noContent())
		);

		RepositoryArgs desired = RepositoryArgs.create("repo").build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse("""
						{
							"default_workflow_permissions": "write",
							"can_approve_pull_request_reviews": false
						}
						""", WorkflowPermissions.class),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/actions/permissions/workflow"
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
		RepositoryArgs desired = RepositoryArgs.create("repo").build();
		var state = goodPublicState();

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				0,
				putRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/actions/permissions/workflow"
						)
				)
		);
	}

	@Test
	void branchProtectionMissing_putsBranchProtection() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.addBranchProtections(
						BranchProtectionArgs.builder("main")
								.enforceAdmins(true)
								.requiredLinearHistory(true)
								.build()
				)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
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
				put(urlEqualTo("/repos/ArloL/repo/immutable-releases"))
						.willReturn(WireMock.noContent())
		);

		var desired = RepositoryArgs.create("repo")
				.immutableReleases(true)
				.build();

		var state = goodPublicState();

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/immutable-releases")
				)
		);
	}

	@Test
	void immutableReleasesEnabled_disablesThem() throws Exception {
		stubFor(
				WireMock.delete(
						urlEqualTo("/repos/ArloL/repo/immutable-releases")
				).willReturn(WireMock.noContent())
		);

		var desired = RepositoryArgs.create("repo").build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				true,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				WireMock.deleteRequestedFor(
						urlEqualTo("/repos/ArloL/repo/immutable-releases")
				)
		);
	}

	@Test
	void branchProtectionDrift_putsBranchProtection() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.addBranchProtections(
						BranchProtectionArgs.builder("main")
								.enforceAdmins(true)
								.requiredLinearHistory(true)
								.build()
				)
				.build();

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
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of("main", driftedBp),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
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
				put(urlEqualTo("/repos/ArloL/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		var bpArgs = BranchProtectionArgs.builder("main")
				.enforceAdmins(true)
				.requiredLinearHistory(true)
				.requiredApprovingReviewCount(1)
				.dismissStaleReviews(true)
				.requireCodeOwnerReviews(true)
				.build();
		RepositoryArgs desired = RepositoryArgs.create("repo")
				.branchProtections(bpArgs)
				.build();

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
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of("main", driftedBp),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
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
				put(urlEqualTo("/repos/ArloL/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		var bpArgs = BranchProtectionArgs.builder("main")
				.enforceAdmins(true)
				.requiredLinearHistory(true)
				.users(List.of("admin-user", "dev-user"))
				.teams(List.of("admins"))
				.apps(List.of("my-app"))
				.build();
		RepositoryArgs desired = RepositoryArgs.create("repo")
				.branchProtections(bpArgs)
				.build();

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
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of("main", driftedBp),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
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
		RepositoryArgs desired = RepositoryArgs.create("repo").build();
		var state = goodPublicState();

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
				)
		);
	}

	@Test
	void repoFieldsAndTopics_bothFixed() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/repo")).willReturn(okJson("{}"))
		);
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/topics"))
						.willReturn(okJson("{\"names\":[\"java\"]}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo")
				.description("correct")
				.topics("java")
				.build();

		var state = stateWithDetailsOverride("""
				{"description": "wrong"}
				""");

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/repo"))
						.withRequestBody(equalToJson("""
								{
									"archived": false,
									"description": "correct",
									"homepage": "",
									"has_issues": true,
									"has_projects": true,
									"has_wiki": true,
									"allow_merge_commit": true,
									"allow_squash_merge": true,
									"allow_rebase_merge": true,
									"allow_update_branch": false,
									"allow_auto_merge": false,
									"delete_branch_on_merge": false,
									"default_branch": "main"
								}
								"""))
		);
		verify(
				putRequestedFor(urlEqualTo("/repos/ArloL/repo/topics"))
						.withRequestBody(equalToJson("{\"names\":[\"java\"]}"))
		);
	}

	// ─── Ruleset tests
	// ──────────────────────────────────────────────────────

	@Test
	void rulesetMissing_postsToCreateRuleset() throws Exception {
		stubFor(
				post(urlEqualTo("/repos/ArloL/repo/rulesets")).willReturn(
						WireMock.status(201).withBody("{\"id\": 1}")
				)
		);

		var desired = RepositoryArgs.create("repo")
				.rulesets(
						RulesetArgs.builder("main-branch-rules")
								.includePatterns("~DEFAULT_BRANCH")
								.requiredLinearHistory(true)
								.noForcePushes(true)
								.requiredStatusChecks(
										StatusCheckArgs.builder()
												.context("CodeQL")
												.build(),
										StatusCheckArgs.builder()
												.context("zizmor")
												.build()
								)
								.build()
				)
				.build();

		var state = goodPublicState(); // no rulesets

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				postRequestedFor(urlEqualTo("/repos/ArloL/repo/rulesets"))
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
				put(urlMatching("/repos/ArloL/repo/rulesets/42"))
						.willReturn(okJson("{\"id\": 42}"))
		);

		var desired = RepositoryArgs.create("repo")
				.rulesets(
						RulesetArgs.builder("main-branch-rules")
								.includePatterns("~DEFAULT_BRANCH")
								.requiredLinearHistory(true)
								.noForcePushes(false)
								.build()
				)
				.build();

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
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(actualRuleset),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(putRequestedFor(urlEqualTo("/repos/ArloL/repo/rulesets/42")));
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

		var desired = RepositoryArgs.create("repo")
				.rulesets(
						RulesetArgs.builder("main-branch-rules")
								.includePatterns("~DEFAULT_BRANCH")
								.requiredLinearHistory(true)
								.requiredStatusChecks(
										StatusCheckArgs.builder()
												.context("CodeQL")
												.build()
								)
								.build()
				)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(actualRuleset),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(0, postRequestedFor(urlEqualTo("/repos/ArloL/repo/rulesets")));
		verify(
				0,
				putRequestedFor(urlMatching("/repos/ArloL/repo/rulesets/.*"))
		);
	}

	@Test
	void rulesetCodeScanning_createsRuleset() throws Exception {
		stubFor(
				post(urlEqualTo("/repos/ArloL/repo/rulesets"))
						.willReturn(WireMock.status(201).withBody("""
								{
									"id": 1,
									"name": "main-branch-rules",
									"target": "branch",
									"enforcement": "active"
								}
								"""))
		);

		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						List.of("~DEFAULT_BRANCH"),
						List.of()
				),
				null,
				null,
				null
		);
		var desired = RepositoryArgs.create("repo")
				.rulesets(
						RulesetArgs.builder("main-branch-rules")
								.includePatterns("~DEFAULT_BRANCH")
								.addRequiredCodeScanning(
										CodeScanningToolArgs.builder()
												.tool("CodeQL")
												.build()
								)
								.build()
				)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				1,
				postRequestedFor(urlEqualTo("/repos/ArloL/repo/rulesets"))
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
				post(urlEqualTo("/repos/ArloL/repo/pages"))
						.willReturn(WireMock.status(201).withBody("""
								{
									"build_type": "workflow",
									"https_enforced": true,
									"public": true,
									"custom_404": false
								}
								"""))
		);

		var desired = RepositoryArgs.create("repo").pages().build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of("github-pages", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				postRequestedFor(urlEqualTo("/repos/ArloL/repo/pages"))
						.withRequestBody(equalToJson("""
								{"build_type": "workflow"}
								"""))
		);
	}

	@Test
	void pagesDrift_putsToUpdate() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/pages"))
						.willReturn(WireMock.noContent())
		);

		var desired = RepositoryArgs.create("repo").pages().build();

		var actualPages = new PagesResponse(
				null,
				"built",
				null,
				false,
				null,
				PagesResponse.BuildType.WORKFLOW,
				null,
				true,
				null,
				null,
				null,
				false // https_enforced is false → drift
		);
		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of("github-pages", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.of(actualPages),
				Map.of(),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(urlEqualTo("/repos/ArloL/repo/pages"))
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
		var desired = RepositoryArgs.create("repo").build(); // pages() not
															 // called

		var state = goodPublicState();

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(0, postRequestedFor(urlEqualTo("/repos/ArloL/repo/pages")));
		verify(0, putRequestedFor(urlEqualTo("/repos/ArloL/repo/pages")));
	}

	// ─── Environment config fix tests
	// ──────────────────────────────────────

	@Test
	void environmentWaitTimerDrift_putsEnvironmentUpdate() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/environments/production"))
						.willReturn(okJson("{}"))
		);

		var desired = RepositoryArgs.create("repo")
				.environment("production", env -> env.waitTimer(30))
				.build();

		var actualEnv = new EnvironmentDetailsResponse(
				"production",
				List.of(
						new EnvironmentDetailsResponse.ProtectionRule(
								EnvironmentDetailsResponse.ProtectionRuleType.WAIT_TIMER,
								10,
								null
						)
				),
				null
		);
		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of("production", actualEnv),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/environments/production")
				).withRequestBody(equalToJson("""
						{"wait_timer": 30}
						"""))
		);
	}

	@Test
	void environmentDeploymentBranchPolicyDrift_putsEnvironmentUpdate()
			throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/environments/production"))
						.willReturn(okJson("{}"))
		);

		var desired = RepositoryArgs.create("repo")
				.environment(
						"production",
						env -> env.deploymentBranchPolicy(true, false)
				)
				.build();

		var actualEnv = new EnvironmentDetailsResponse(
				"production",
				List.of(),
				new EnvironmentDetailsResponse.DeploymentBranchPolicy(
						false,
						true
				)
		);
		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of("production", actualEnv),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/environments/production")
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
		var desired = RepositoryArgs.create("repo")
				.environment("production", env -> env.waitTimer(30))
				.build();

		var actualEnv = new EnvironmentDetailsResponse(
				"production",
				List.of(
						new EnvironmentDetailsResponse.ProtectionRule(
								EnvironmentDetailsResponse.ProtectionRuleType.WAIT_TIMER,
								30,
								null
						)
				),
				null
		);
		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of("production", actualEnv),
				false,
				false,
				false
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				0,
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/environments/production")
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
								"/repos/ArloL/repo/actions/secrets/public-key"
						)
				).willReturn(okJson("""
						{"key_id": "123", "key": "%s"}
						""".formatted(TEST_PUBLIC_KEY)))
		);
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/actions/secrets/PAT"))
						.willReturn(WireMock.status(201))
		);

		var localChecker = checkerWithSecrets(
				wm,
				Map.of("repo-PAT", "ghp_test_value")
		);
		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);
		var desired = RepositoryArgs.create("repo")
				.actionsSecrets("PAT")
				.build();

		List<String> diffs = localChecker.computeDiffs(state, desired);
		List<String> remaining = localChecker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				1,
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/actions/secrets/PAT")
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
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);
		var desired = RepositoryArgs.create("repo")
				.actionsSecrets("PAT")
				.build();

		List<String> diffs = localChecker.computeDiffs(state, desired);
		List<String> remaining = localChecker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).anyMatch(
				d -> d.contains("action_secrets") && d.contains("missing")
						&& d.contains("PAT")
		);
		verify(
				0,
				putRequestedFor(
						urlMatching("/repos/ArloL/repo/actions/secrets/.*")
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
								"/repos/ArloL/repo/environments/production/secrets/public-key"
						)
				).willReturn(okJson("""
						{"key_id": "456", "key": "%s"}
						""".formatted(TEST_PUBLIC_KEY)))
		);
		stubFor(
				put(
						urlEqualTo(
								"/repos/ArloL/repo/environments/production/secrets/TF_GITHUB_TOKEN"
						)
				).willReturn(WireMock.status(201))
		);

		var localChecker = checkerWithSecrets(
				wm,
				Map.of("repo-production-TF_GITHUB_TOKEN", "ghp_test_value")
		);
		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				false,
				Map.of(
						"main",
						parse(
								GOOD_BRANCH_PROTECTION_JSON,
								BranchProtectionResponse.class
						)
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of(
						"production",
						parse("{}", EnvironmentDetailsResponse.class)
				),
				false,
				false,
				false
		);
		var desired = RepositoryArgs.create("repo")
				.environment(
						"production",
						env -> env.secrets("TF_GITHUB_TOKEN")
				)
				.build();

		List<String> diffs = localChecker.computeDiffs(state, desired);
		List<String> remaining = localChecker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				1,
				putRequestedFor(
						urlEqualTo(
								"/repos/ArloL/repo/environments/production/secrets/TF_GITHUB_TOKEN"
						)
				)
		);
	}

}
