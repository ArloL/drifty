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
							.usingFilesUnderClasspath("wiremock")
			)
			.build();

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
		assertThat(enabled).isFalse();
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
		var perms = new WorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				true
		);
		assertThatNoException().isThrownBy(
				() -> client.updateWorkflowPermissions(
						"ArloL",
						"drifty-test",
						perms
				)
		);
	}

	@Test
	void replaceTopics_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client.replaceTopics("ArloL", "drifty-test", List.of())
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
		assertThat(enabled).isFalse();
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

}
