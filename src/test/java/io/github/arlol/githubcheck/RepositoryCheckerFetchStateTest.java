package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Covers {@link RepositoryChecker#fetchState}, which fans out across the whole
 * read side of the GitHub API. The three cases are the three shapes that
 * fan-out takes: a public repository (everything is fetched), a private one
 * (branch protections are skipped) and an archived one (GitHub does not expose
 * the security settings, so they all read false).
 */
@WireMockTest
class RepositoryCheckerFetchStateTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	/**
	 * The primitive fields the client's strict mapper requires; the tests vary
	 * only the {@code security_and_analysis} block on top of this.
	 */
	private static final String REPO_DETAILS_FIELDS = """
			"id": 1,
			"name": "repo",
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
			""";

	private static final RepoRef REF = new RepoRef("owner", "repo");

	private RepositoryChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		checker = new RepositoryChecker(client, false);
	}

	@Test
	void publicRepo_fetchesEverything() throws Exception {
		stubRepoDetails("""
				,"security_and_analysis": {
					"secret_scanning": {"status": "enabled"},
					"secret_scanning_push_protection": {"status": "enabled"},
					"secret_scanning_non_provider_patterns": {
						"status": "disabled"
					},
					"secret_scanning_validity_checks": {"status": "enabled"}
				}
				""");
		stubSecurityEndpoints();
		stubStandardEndpoints();

		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/branches"))
						.willReturn(okJson("""
								[{"name": "main", "protected": true}]
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/owner/repo/branches/main/protection"
						)
				).willReturn(okJson("""
						{
							"enforce_admins": {"enabled": true},
							"required_linear_history": {"enabled": true},
							"allow_force_pushes": {"enabled": false},
							"allow_deletions": {"enabled": false}
						}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(okJson("""
								[{"id": 42, "name": "main-rules"}]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets/42"))
						.willReturn(okJson("""
								{"id": 42, "name": "main-rules", "rules": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(okJson("""
										{
									"build_type": "workflow",
									"custom_404": false,
									"public": true,
									"https_enforced": true
								}
										"""))
		);

		RepositoryState state = checker.fetchState(
				REF,
				summary(false, "public"),
				ManagedGroups.all(Drifty.GroupName.class)
		);

		assertThat(state.name()).isEqualTo("repo");
		assertThat(state.vulnerabilityAlerts()).isTrue();
		assertThat(state.automatedSecurityFixes()).isTrue();
		assertThat(state.immutableReleases()).isTrue();
		assertThat(state.privateVulnerabilityReporting()).isTrue();
		assertThat(state.codeScanningDefaultSetup()).isTrue();
		assertThat(state.securityAndAnalysis().secretScanning()).isTrue();
		assertThat(state.securityAndAnalysis().secretScanningPushProtection())
				.isTrue();
		assertThat(
				state.securityAndAnalysis().secretScanningNonProviderPatterns()
		).isFalse();
		assertThat(state.securityAndAnalysis().secretScanningValidityChecks())
				.isTrue();

		assertThat(state.branchProtections()).containsOnlyKeys("main");
		assertThat(state.branchProtections().get("main").enforceAdmins())
				.isTrue();
		assertThat(state.rulesets()).singleElement()
				.satisfies(r -> assertThat(r.name()).isEqualTo("main-rules"));
		assertThat(state.pages()).isPresent();
		assertThat(state.actionSecrets()).singleElement()
				.satisfies(s -> assertThat(s.name()).isEqualTo("TOKEN"));
		assertThat(state.environments()).containsOnlyKeys("prod");
		assertThat(state.environmentSecrets().get("prod")).singleElement()
				.satisfies(s -> assertThat(s.name()).isEqualTo("DEPLOY_KEY"));
		assertThat(state.workflowPermissions().defaultWorkflowPermissions())
				.isEqualTo(
						WorkflowPermissions.DefaultWorkflowPermissions.WRITE
				);
		assertThat(state.workflowPermissions().canApprovePullRequestReviews())
				.isTrue();
	}

	@Test
	void privateRepo_skipsBranchProtections() throws Exception {
		stubRepoDetails("");
		stubSecurityEndpoints();
		stubStandardEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		RepositoryState state = checker.fetchState(
				REF,
				summary(false, "private"),
				ManagedGroups.all(Drifty.GroupName.class)
		);

		assertThat(state.branchProtections()).isEmpty();
		assertThat(state.rulesets()).isEmpty();
		assertThat(state.pages()).isEmpty();
		// A repository with no security_and_analysis block reads as disabled
		// rather than blowing up on the missing object.
		assertThat(state.securityAndAnalysis().secretScanning()).isFalse();
		assertThat(state.securityAndAnalysis().secretScanningPushProtection())
				.isFalse();
		assertThat(
				state.securityAndAnalysis().secretScanningNonProviderPatterns()
		).isFalse();
		assertThat(state.securityAndAnalysis().secretScanningValidityChecks())
				.isFalse();
		assertThat(state.vulnerabilityAlerts()).isTrue();
	}

	@Test
	void archivedRepo_skipsSecuritySettings() throws Exception {
		stubRepoDetails("""
				,"security_and_analysis": {
					"secret_scanning": {"status": "enabled"}
				}
				""");
		stubStandardEndpoints();

		RepositoryState state = checker.fetchState(
				REF,
				summary(true, "public"),
				ManagedGroups.all(Drifty.GroupName.class)
		);

		// None of the security endpoints are stubbed: reaching any of them
		// would fail the request, so passing proves they are all skipped.
		assertThat(state.vulnerabilityAlerts()).isFalse();
		assertThat(state.automatedSecurityFixes()).isFalse();
		assertThat(state.immutableReleases()).isFalse();
		assertThat(state.privateVulnerabilityReporting()).isFalse();
		assertThat(state.codeScanningDefaultSetup()).isFalse();

		// The secret-scanning toggles ride along on the repository response,
		// which is fetched for archived repos too, so they report what it says
		// rather than being zeroed. Zeroing them made an archived repo that
		// config wants unarchived report drift on settings that were already
		// correct.
		assertThat(state.securityAndAnalysis().secretScanning()).isTrue();
		assertThat(state.securityAndAnalysis().secretScanningPushProtection())
				.isFalse();
		assertThat(
				state.securityAndAnalysis().secretScanningNonProviderPatterns()
		).isFalse();
		assertThat(state.securityAndAnalysis().secretScanningValidityChecks())
				.isFalse();

		assertThat(state.branchProtections()).isEmpty();
		assertThat(state.rulesets()).isEmpty();
		assertThat(state.pages()).isEmpty();
		// Secrets, environments and workflow permissions are still read.
		assertThat(state.actionSecrets()).hasSize(1);
		assertThat(state.environments()).containsOnlyKeys("prod");
	}

	@Test
	void immutableReleasesAbsent_readsAsDisabled() throws Exception {
		stubRepoDetails("");
		stubSecurityEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/immutable-releases"))
						.willReturn(aResponse().withStatus(404))
		);
		stubStandardEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		RepositoryState state = checker.fetchState(
				REF,
				summary(false, "private"),
				ManagedGroups.all(Drifty.GroupName.class)
		);

		assertThat(state.immutableReleases()).isFalse();
	}

	@Test
	void orgLevelRulesets_areNotFetchedOrReported() throws Exception {
		stubRepoDetails("");
		stubSecurityEndpoints();
		stubStandardEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/branches"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(okJson("""
								[
									{
										"id": 42,
										"name": "repo-rules",
										"source_type": "Repository"
									},
									{
										"id": 99,
										"name": "org-rules",
										"source_type": "Organization"
									}
								]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets/42"))
						.willReturn(okJson("""
								{"id": 42, "name": "repo-rules", "rules": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		RepositoryState state = checker.fetchState(
				REF,
				summary(false, "public"),
				ManagedGroups.all(Drifty.GroupName.class)
		);

		assertThat(state.rulesets()).singleElement()
				.satisfies(r -> assertThat(r.name()).isEqualTo("repo-rules"));
		verify(
				0,
				getRequestedFor(urlPathEqualTo("/repos/owner/repo/rulesets/99"))
		);
	}

	@Test
	void unmanagedGroups_endpointsAreNeverRequested() throws Exception {
		stubRepoDetails("");
		stubSecurityEndpoints();
		stubStandardEndpoints();
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/actions/secrets"))
						.willReturn(aResponse().withStatus(403))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/rulesets"))
						.willReturn(aResponse().withStatus(403))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/branches"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		ManagedGroups<Drifty.GroupName> managed = ManagedGroups.of(
				new Drifty.Managed(
						Drifty.ManageMode.ALL_EXCEPT,
						List.of(
								Drifty.GroupName.ACTION_SECRETS,
								Drifty.GroupName.RULESETS
						)
				)
		);

		RepositoryState state = checker
				.fetchState(REF, summary(false, "public"), managed);

		assertThat(state.actionSecrets()).isEmpty();
		assertThat(state.rulesets()).isEmpty();
		verify(
				0,
				getRequestedFor(
						urlPathEqualTo("/repos/owner/repo/actions/secrets")
				)
		);
		verify(
				0,
				getRequestedFor(urlPathEqualTo("/repos/owner/repo/rulesets"))
		);
	}

	private static RepositorySummaryResponse summary(
			boolean archived,
			String visibility
	) throws Exception {
		return MAPPER.readValue(
				"""
						{
							"name": "repo",
							"archived": %s,
							"visibility": "%s"
						}
						""".formatted(archived, visibility),
				RepositorySummaryResponse.class
		);
	}

	private static void stubRepoDetails(String extraFields) {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo")).willReturn(
						okJson("{" + REPO_DETAILS_FIELDS + extraFields + "}")
				)
		);
	}

	private static void stubSecurityEndpoints() {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(204))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/owner/repo/automated-security-fixes"
						)
				).willReturn(okJson("""
						{"enabled": true}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/immutable-releases"))
						.willReturn(okJson("""
								{"enabled": true}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/owner/repo/private-vulnerability-reporting"
						)
				).willReturn(okJson("""
						{"enabled": true}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/owner/repo/code-scanning/default-setup"
						)
				).willReturn(okJson("""
						{"state": "configured"}
						"""))
		);
	}

	private static void stubStandardEndpoints() {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": [{"name": "TOKEN"}]}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo/environments"))
						.willReturn(okJson("""
								{"environments": [{"name": "prod"}]}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/owner/repo/environments/prod/secrets"
						)
				).willReturn(okJson("""
						{"secrets": [{"name": "DEPLOY_KEY"}]}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/owner/repo/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
							"default_workflow_permissions": "write",
							"can_approve_pull_request_reviews": true
						}
						"""))
		);
	}

}
