package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;

class GitHubClientPlaybackTest {

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					wireMockConfig().dynamicPort()
							.usingFilesUnderDirectory(
									"src/test/resources/wiremock"
							)
			)
			.build();

	private String owner = "ArloL";
	private String repo = "drifty-test";
	private GitHubClient client;

	@BeforeEach
	void setUp() {
		client = new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"test-token"
		);
	}

	@Test
	void listOrgRepos_returnsRecordedRepos() throws Exception {
		List<RepositorySummaryResponse> repos = client.listOrgRepos("ArloL");
		assertThat(repos).isNotEmpty();
		assertThat(repos).extracting(RepositorySummaryResponse::name)
				.contains("drifty");
	}

	@Test
	void getRepo_returnsRecordedDetails() throws Exception {
		RepositoryDetailsResponse repo = client.getRepo("ArloL", "drifty-test");
		assertThat(repo.name()).isEqualTo("drifty-test");
		assertThat(repo.visibility()).isEqualTo(RepositoryVisibility.PUBLIC);
		assertThat(repo.description())
				.isEqualTo("A test project only to test things with drifty");
		assertThat(repo.allowMergeCommit()).isTrue();
		assertThat(repo.allowSquashMerge()).isTrue();
		assertThat(repo.allowRebaseMerge()).isTrue();
		assertThat(repo.allowAutoMerge()).isFalse();
		assertThat(repo.deleteBranchOnMerge()).isFalse();
		assertThat(repo.topics()).isEmpty();
	}

	@Test
	void getVulnerabilityAlerts_returnsRecordedState() throws Exception {
		boolean enabled = client.getVulnerabilityAlerts("ArloL", "drifty-test");
		assertThat(enabled).isTrue();
	}

	@Test
	void getImmutableReleases_succeeds() {
		assertThatNoException().isThrownBy(() -> {
			client.getImmutableReleases("ArloL", "drifty-test");
		});
	}

	@Test
	void getWorkflowPermissions_returnsRecordedPermissions() throws Exception {
		WorkflowPermissions perms = client
				.getWorkflowPermissions("ArloL", "drifty-test");
		assertThat(perms.defaultWorkflowPermissions())
				.isEqualTo(DefaultWorkflowPermissions.READ);
		assertThat(perms.canApprovePullRequestReviews()).isFalse();
	}

	@Test
	void getAutomatedSecurityFixes_returnsRecordedState() throws Exception {
		boolean enabled = client
				.getAutomatedSecurityFixes("ArloL", "drifty-test");
		assertThat(enabled).isFalse();
	}

	@Test
	void enableVulnerabilityAlerts_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.enableVulnerabilityAlerts("ArloL", "drifty-test")
		);
	}

	@Test
	void updateWorkflowPermissions_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.updateWorkflowPermissions(
						owner,
						repo,
						new WorkflowPermissions(
								WorkflowPermissions.DefaultWorkflowPermissions.READ,
								false
						)
				)
		);
	}

	@Test
	void replaceTopics_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client
						.replaceTopics("ArloL", "drifty-test", List.of("test"))
		);
	}

	@Test
	void updateRepository_defaultBranch_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.updateRepository(
						"ArloL",
						"drifty-test",
						RepositoryUpdateRequest.builder()
								.defaultBranch("main")
								.build()
				)
		);
	}

	@Test
	void listRulesets_succeeds() {
		assertThatNoException().isThrownBy(() -> {
			var rulesets = client.listRulesets("ArloL", "drifty-test");
			assertThat(rulesets).isNotEmpty();
			assertThat(rulesets).extracting(RulesetSummaryResponse::name)
					.contains("main-branch-rules");
			var ruleset = client.getRuleset(
					"ArloL",
					"drifty-test",
					rulesets.getFirst().id()
			);
			assertThat(ruleset).isNotNull();
			assertThat(ruleset.conditions()).isNotNull();
			assertThat(ruleset.conditions().refName()).isNotNull();
			assertThat(ruleset.conditions().refName().include())
					.contains("refs/heads/main");
		});
	}

	@Test
	void getPages_succeeds() {
		assertThatNoException().isThrownBy(() -> {
			client.getPages("ArloL", "drifty-test");
			client.deletePages("ArloL", "drifty-test");
		});
	}

	@Test
	void getEnvironments_returnsRecordedEnvironments() throws Exception {
		var environments = client.getEnvironments("ArloL", "drifty-test");
		assertThat(environments).hasSize(2);
		assertThat(environments).extracting(EnvironmentDetailsResponse::name)
				.containsExactlyInAnyOrder("production", "github-pages");
		var production = environments.stream()
				.filter(e -> e.name().equalsIgnoreCase("production"))
				.findFirst()
				.orElseThrow();
		assertThat(production.getWaitTimer()).isEqualTo(30);
		assertThat(production.getReviewerIds()).isEmpty();
		assertThat(production.deploymentBranchPolicy()).isNull();
	}

	@Test
	void updateEnvironment_succeeds() {
		var payload = new EnvironmentUpdateRequest(30, null, null);
		assertThatNoException().isThrownBy(
				() -> client.updateEnvironment(
						"ArloL",
						"drifty-test",
						"production",
						payload
				)
		);
	}

	@Test
	void getPrivateVulnerabilityReporting_returnsRecordedState()
			throws Exception {
		boolean enabled = client
				.getPrivateVulnerabilityReporting("ArloL", "drifty-test");
		assertThat(enabled).isFalse();
	}

	@Test
	void getCodeScanningDefaultSetup_returnsRecordedState() throws Exception {
		boolean enabled = client
				.getCodeScanningDefaultSetup("ArloL", "drifty-test");
		assertThat(enabled).isTrue();
	}

	@Test
	void getBranchProtection_succeeds() throws Exception {
		BranchProtectionResponse bp = client
				.getBranchProtection("ArloL", "drifty-test", "main")
				.orElseThrow();
		assertThat(bp.enforceAdmins().enabled()).isTrue();
		assertThat(bp.requiredLinearHistory().enabled()).isTrue();
		assertThat(bp.allowForcePushes().enabled()).isFalse();
	}

	@Test
	void disableVulnerabilityAlerts_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.disableVulnerabilityAlerts("ArloL", "drifty-test")
		);
	}

	@Test
	void enablePrivateVulnerabilityReporting_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.enablePrivateVulnerabilityReporting(
						"ArloL",
						"drifty-test"
				)
		);
	}

	@Test
	void disablePrivateVulnerabilityReporting_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.disablePrivateVulnerabilityReporting(
						"ArloL",
						"drifty-test"
				)
		);
	}

	@Test
	void enableCodeScanningDefaultSetup_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client
						.enableCodeScanningDefaultSetup("ArloL", "drifty-test")
		);
	}

	@Test
	void disableCodeScanningDefaultSetup_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client
						.disableCodeScanningDefaultSetup("ArloL", "drifty-test")
		);
	}

	@Test
	void getActionSecretNames_returnsRecordedSecrets() throws Exception {
		List<String> names = client
				.getActionSecretNames("ArloL", "drifty-test");
		assertThat(names).contains("ACTION_SECRET");
	}

	@Test
	void createOrUpdateActionSecret_succeeds() {
		assertThatNoException().isThrownBy(() -> {
			var actionKey = client.getActionSecretPublicKey(owner, repo);
			client.createOrUpdateActionSecret(
					owner,
					repo,
					"ACTION_SECRET",
					new SecretRequest(
							"b+IVZvhDLJmeYKNNAWpPg2rSPzvp7p11oENe/V8YU37SnEbUPlGugWCh1cvRuHhy9yZRQyTBR7vVPRhYy1o03LI=",
							actionKey.keyId()
					)
			);
		});
	}

	@Test
	void getEnvironmentSecretNames_returnsRecordedSecrets() throws Exception {
		List<String> names = client.getEnvironmentSecretNames(
				"ArloL",
				"drifty-test",
				"production"
		);
		assertThat(names).contains("ENV_SECRET");
	}

	@Test
	void createOrUpdateEnvironmentSecret_succeeds() {
		assertThatNoException().isThrownBy(() -> {
			var envKey = client
					.getEnvironmentSecretPublicKey(owner, repo, "production");
			client.createOrUpdateEnvironmentSecret(
					"ArloL",
					repo,
					"production",
					"ENV_SECRET",
					new SecretRequest(
							"YNybzgE1h2YUsH0B3rjsTEM3ioG4Hstw7yG1CGPWEWvj2egzlqbewHejFrwuSvLEt4xytzM/+gLDh1WPmHKNWN8=",
							envKey.keyId()
					)
			);
		});
	}

	@Test
	void getBranches_returnsRecordedBranches() throws Exception {
		List<BranchResponse> branches = client
				.getBranches("ArloL", "drifty-test", false);
		assertThat(branches).isNotEmpty();
		assertThat(branches).extracting(BranchResponse::name).contains("main");
	}

	@Test
	void enableImmutableReleases_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.enableImmutableReleases("ArloL", "drifty-test")
		);
	}

	@Test
	void disableImmutableReleases_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.disableImmutableReleases("ArloL", "drifty-test")
		);
	}

	@Test
	void deleteEnvironment_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client
						.deleteEnvironment("ArloL", "drifty-test", "production")
		);
	}

	@Test
	void getAuthenticatedUser_returnsRecordedUser() throws Exception {
		SimpleUser user = client.getAuthenticatedUser();
		assertThat(user.login()).isNotBlank();
	}

	@Test
	void updateBranchProtection_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.updateBranchProtection(
						"ArloL",
						"drifty-test",
						"main",
						new BranchProtectionRequest(
								null,
								true,
								null,
								null,
								true,
								false
						)
				)
		);
	}

}
