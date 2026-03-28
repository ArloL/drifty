package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
import io.github.arlol.githubcheck.client.EnvironmentReviewerType;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryFull;
import io.github.arlol.githubcheck.client.RepositoryMinimal;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;

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
				"allow_merge_commit": false,
				"allow_squash_merge": false,
				"allow_auto_merge": true,
				"delete_branch_on_merge": true,
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
					"checks": [
						{"context": "check-actions.required-status-check"},
						{"context": "codeql-analysis.required-status-check"},
						{"context": "CodeQL"},
						{"context": "zizmor"}
					]
				}
			}
			""";

	private static final String GOOD_WORKFLOW_PERMISSIONS_JSON = """
			{
				"default_workflow_permissions": "read",
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
				"allow_merge_commit": false,
				"allow_squash_merge": false,
				"allow_auto_merge": true,
				"delete_branch_on_merge": true
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of()
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of()
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
									"allow_merge_commit": false,
									"allow_squash_merge": false,
									"allow_auto_merge": true,
									"delete_branch_on_merge": true
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
									"allow_merge_commit": false,
									"allow_squash_merge": false,
									"allow_auto_merge": true,
									"delete_branch_on_merge": true
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
				true,
				state.branchProtection(),
				state.actionSecretNames(),
				state.environmentSecretNames(),
				state.workflowPermissions(),
				List.of(),
				Optional.empty(),
				Map.of()
		);

		List<String> diffs = checker.computeDiffs(stateWithBadVuln, desired);
		List<String> remaining = checker
				.applyFixes("repo", stateWithBadVuln, desired, diffs);

		assertThat(remaining).containsExactlyInAnyOrder(
				"default_branch: want=main got=master"
		);
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/vulnerability-alerts")
				)
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

		RepositoryArgs desired = RepositoryArgs.create("repo").build();

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
				baseState.branchProtection(),
				baseState.actionSecretNames(),
				baseState.environmentSecretNames(),
				baseState.workflowPermissions(),
				List.of(),
				Optional.empty(),
				Map.of()
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

		RepositoryArgs desired = RepositoryArgs.create("repo").build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				false,
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of()
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
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
				Map.of()
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
							"default_workflow_permissions": "read",
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

		RepositoryArgs desired = RepositoryArgs.create("repo").build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				true,
				null,
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of()
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
				).withRequestBody(
						equalToJson(
								"""
										{
											"required_status_checks": {
												"strict": false,
												"checks": [
													{"context": "check-actions.required-status-check"},
													{"context": "codeql-analysis.required-status-check"},
													{"context": "CodeQL"},
													{"context": "zizmor"}
												]
											},
											"enforce_admins": true,
											"required_pull_request_reviews": null,
											"restrictions": null,
											"required_linear_history": true,
											"allow_force_pushes": false
										}
										"""
						)
				)
		);
	}

	@Test
	void branchProtectionDrift_putsBranchProtection() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/repo/branches/main/protection"))
						.willReturn(okJson("{}"))
		);

		RepositoryArgs desired = RepositoryArgs.create("repo").build();

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
				true,
				driftedBp,
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of()
		);

		List<String> diffs = checker.computeDiffs(state, desired);
		List<String> remaining = checker
				.applyFixes("repo", state, desired, diffs);

		assertThat(remaining).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/repos/ArloL/repo/branches/main/protection")
				).withRequestBody(
						equalToJson(
								"""
										{
											"required_status_checks": {
												"strict": false,
												"checks": [
													{"context": "check-actions.required-status-check"},
													{"context": "codeql-analysis.required-status-check"},
													{"context": "CodeQL"},
													{"context": "zizmor"}
												]
											},
											"enforce_admins": true,
											"required_pull_request_reviews": null,
											"restrictions": null,
											"required_linear_history": true,
											"allow_force_pushes": false
										}
										"""
						)
				)
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
									"allow_merge_commit": false,
									"allow_squash_merge": false,
									"allow_auto_merge": true,
									"delete_branch_on_merge": true
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
								.requiredStatusChecks("CodeQL", "zizmor")
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(actualRuleset),
				Optional.empty(),
				Map.of()
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
		var params = new RulesetDetailsResponse.Rule.Parameters(
				List.of(
						new RulesetDetailsResponse.Rule.Parameters.StatusCheck(
								"CodeQL",
								null
						)
				),
				false,
				null,
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
						new RulesetDetailsResponse.Rule(
								RulesetRuleType.REQUIRED_LINEAR_HISTORY,
								null
						),
						new RulesetDetailsResponse.Rule(
								RulesetRuleType.REQUIRED_STATUS_CHECKS,
								params
						)
				)
		);

		var desired = RepositoryArgs.create("repo")
				.rulesets(
						RulesetArgs.builder("main-branch-rules")
								.includePatterns("~DEFAULT_BRANCH")
								.requiredLinearHistory(true)
								.requiredStatusChecks("CodeQL")
								.build()
				)
				.build();

		var state = new RepositoryState(
				"repo",
				parse(GOOD_SUMMARY_JSON, RepositoryMinimal.class),
				parse(GOOD_DETAILS_JSON, RepositoryFull.class),
				true,
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of(),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(actualRuleset),
				Optional.empty(),
				Map.of()
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of("github-pages", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of()
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of("github-pages", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.of(actualPages),
				Map.of()
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of("production", actualEnv)
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of("production", actualEnv)
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
				true,
				parse(
						GOOD_BRANCH_PROTECTION_JSON,
						BranchProtectionResponse.class
				),
				List.of(),
				Map.of("production", List.of()),
				parse(
						GOOD_WORKFLOW_PERMISSIONS_JSON,
						WorkflowPermissions.class
				),
				List.of(),
				Optional.empty(),
				Map.of("production", actualEnv)
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

}
