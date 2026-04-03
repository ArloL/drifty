package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.awaitility.Awaitility.given;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

class GitHubClientRecordingTest {

	static final Path MAPPINGS_DIR = Path
			.of("src/test/resources/wiremock/mappings");

	static final Path FILES_DIR = Path
			.of("src/test/resources/wiremock/__files");

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					wireMockConfig().dynamicPort()
							.usingFilesUnderDirectory(
									"src/test/resources/wiremock"
							)
			)
			.build();

	@Test
	void record_gitHubApiInteractions() throws Exception {
		String token = System.getenv("DRIFTY_GITHUB_TOKEN");
		assumeThat(token).isNotBlank();
		String wiremockRecord = System.getenv("DRIFTY_WIREMOCK_RECORD");
		assumeThat(wiremockRecord).isNotBlank();

		clearDirectory(MAPPINGS_DIR);
		clearDirectory(FILES_DIR);

		wm.startRecording(
				recordSpec().forTarget("https://api.github.com")
						.makeStubsPersistent(true)
						.ignoreRepeatRequests()
						.build()
		);

		var client = new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				token
		);

		String repo = "drifty-test";
		String owner = client.getAuthenticatedUser().login();
		var repositoryCreateRequest = RepositoryCreateRequest.builder()
				.name(repo)
				.autoInit(true)
				.description("A test project only to test things with drifty")
				.build();

		try {
			client.createUserRepository(repositoryCreateRequest);
		} catch (GitHubApiException e) {
			client.deleteRepository(owner, repo);
			client.createUserRepository(repositoryCreateRequest);
		}

		given().ignoreExceptions()
				.await()
				.until(() -> client.getRepo(owner, repo) != null);

		try {
			client.listOrgRepos(owner);
			var apiRepo = client.getRepo(owner, repo);
			client.getVulnerabilityAlerts(owner, repo);
			client.getAutomatedSecurityFixes(owner, repo);
			client.getImmutableReleases(owner, repo);
			client.getPrivateVulnerabilityReporting(owner, repo);
			client.getCodeScanningDefaultSetup(owner, repo);
			client.updateBranchProtection(
					owner,
					repo,
					apiRepo.defaultBranch(),
					new BranchProtectionRequest(
							null,
							true,
							null,
							null,
							true,
							false
					)
			);
			var branches = client.getBranches(owner, repo, true);
			for (var branch : branches) {
				client.getBranchProtection(owner, repo, branch.name());
			}
			client.getWorkflowPermissions(owner, repo);
			client.enableVulnerabilityAlerts(owner, repo);
			client.updateWorkflowPermissions(
					owner,
					repo,
					new WorkflowPermissions(
							WorkflowPermissions.DefaultWorkflowPermissions.READ,
							true
					)
			);
			client.replaceTopics(owner, repo, List.of());
			client.updateRepository(
					owner,
					repo,
					RepositoryUpdateRequest.builder()
							.defaultBranch("main")
							.build()
			);
			client.createRuleset(
					owner,
					repo,
					new RulesetRequest(
							"main-branch-rules",
							RulesetTarget.BRANCH,
							RulesetEnforcement.ACTIVE,
							List.of(),
							new RulesetRequest.Conditions(
									new RulesetRequest.Conditions.RefName(
											List.of(
													"refs/heads/" + apiRepo
															.defaultBranch()
											),
											List.of()
									),
									null,
									null,
									null
							),
							List.of(new Rule.RequiredLinearHistory())
					)
			);
			var rulesets = client.listRulesets(owner, repo);
			client.getRuleset(owner, repo, rulesets.getFirst().id());
			client.getPages(owner, repo);
			client.createPages(
					owner,
					repo,
					new PagesCreateRequest(PagesBuildType.WORKFLOW, null)
			);
			client.updatePages(
					owner,
					repo,
					new PagesUpdateRequest(PagesBuildType.WORKFLOW, null, null)
			);
			client.deletePages(owner, repo);

			client.createOrUpdateEnvironment(
					owner,
					repo,
					"production",
					new EnvironmentUpdateRequest(15, null, null)
			);
			client.updateEnvironment(
					owner,
					repo,
					"production",
					new EnvironmentUpdateRequest(30, null, null)
			);
			client.getEnvironments(owner, repo);
			client.deleteEnvironment(owner, repo, "production");
		} finally {
			client.deleteRepository(owner, repo);
		}

		wm.stopRecording();
	}

	private static void clearDirectory(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (var stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile).forEach(file -> {
				try {
					Files.delete(file);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
