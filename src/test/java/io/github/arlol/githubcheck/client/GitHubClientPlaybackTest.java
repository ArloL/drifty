package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.arlol.githubcheck.client.RepositoryFull;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
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
		List<RepositoryMinimal> repos = client.listOrgRepos("ArloL");
		assertThat(repos).isNotEmpty();
		assertThat(repos).extracting(RepositoryMinimal::name)
				.contains("terraform-github");
	}

	@Test
	void getRepo_returnsRecordedDetails() throws Exception {
		RepositoryFull repo = client.getRepo("ArloL", "terraform-github");
		assertThat(repo.name()).isEqualTo("terraform-github");
		assertThat(repo.visibility()).isEqualTo(RepositoryVisibility.PUBLIC);
		assertThat(repo.description()).isEqualTo(
				"A project to manage github settings with terraform"
		);
		assertThat(repo.allowMergeCommit()).isFalse();
		assertThat(repo.allowSquashMerge()).isFalse();
		assertThat(repo.allowAutoMerge()).isTrue();
		assertThat(repo.deleteBranchOnMerge()).isTrue();
		assertThat(repo.topics()).isEmpty();
	}

	@Test
	void getVulnerabilityAlerts_returnsRecordedState() throws Exception {
		boolean enabled = client
				.getVulnerabilityAlerts("ArloL", "terraform-github");
		assertThat(enabled).isTrue();
	}

	@Test
	void getWorkflowPermissions_returnsRecordedPermissions() throws Exception {
		WorkflowPermissions perms = client
				.getWorkflowPermissions("ArloL", "terraform-github");
		assertThat(perms.defaultWorkflowPermissions())
				.isEqualTo(DefaultWorkflowPermissions.READ);
		assertThat(perms.canApprovePullRequestReviews()).isTrue();
	}

	@Test
	void enableVulnerabilityAlerts_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client
						.enableVulnerabilityAlerts("ArloL", "terraform-github")
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
						"terraform-github",
						perms
				)
		);
	}

	@Test
	void replaceTopics_succeeds() {
		assertThatNoException().isThrownBy(
				() -> client
						.replaceTopics("ArloL", "terraform-github", List.of())
		);
	}

	@Test
	void listRulesets_succeeds() {
		assertThatNoException().isThrownBy(() -> {
			var rulesets = client.listRulesets("ArloL", "terraform-github");
			assertThat(rulesets).isNotEmpty();
			assertThat(rulesets).extracting(RulesetSummaryResponse::name)
					.contains("main-branch-rules");
			var ruleset = client.getRuleset(
					"ArloL",
					"terraform-github",
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
			client.getPages("ArloL", "terraform-github");
			client.deletePages("ArloL", "terraform-github");
		});
	}

	@Test
	void getEnvironments_returnsRecordedEnvironments() throws Exception {
		var environments = client.getEnvironments("ArloL", "terraform-github");
		assertThat(environments).hasSize(1);
		assertThat(environments).extracting(EnvironmentDetailsResponse::name)
				.containsExactly("production");
		var production = environments.getFirst();
		assertThat(production.getWaitTimer()).isEqualTo(30);
		assertThat(production.getReviewerIds()).containsExactly("USER:1234567");
		assertThat(production.deploymentBranchPolicy()).isNotNull();
		assertThat(production.deploymentBranchPolicy().protectedBranches())
				.isTrue();
	}

	@Test
	void updateEnvironment_succeeds() {
		var payload = new EnvironmentUpdateRequest(30, null, null);
		assertThatNoException().isThrownBy(
				() -> client.updateEnvironment(
						"ArloL",
						"terraform-github",
						"production",
						payload
				)
		);
	}

}
