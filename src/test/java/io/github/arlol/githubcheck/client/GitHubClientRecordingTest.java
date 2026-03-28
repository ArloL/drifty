package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assumptions.assumeThat;

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
		String token = System.getenv("GITHUB_TOKEN");
		assumeThat(token).isNotBlank();

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
		client.listOrgRepos("ArloL");
		client.getRepo("ArloL", "terraform-github");
		client.getVulnerabilityAlerts("ArloL", "terraform-github");
		var perms = client.getWorkflowPermissions("ArloL", "terraform-github");
		// Non-destructive writes: write back what was just read (idempotent)
		client.enableVulnerabilityAlerts("ArloL", "terraform-github");
		client.updateWorkflowPermissions("ArloL", "terraform-github", perms);
		client.replaceTopics("ArloL", "terraform-github", List.of());
		var rulesets = client.listRulesets("ArloL", "terraform-github");
		client.getRuleset(
				"ArloL",
				"terraform-github",
				rulesets.getFirst().id()
		);
		client.getPages("ArloL", "terraform-github");
		client.deletePages("ArloL", "terraform-github");
		var environments = client.getEnvironments("ArloL", "terraform-github");
		if (!environments.isEmpty()) {
			var env = environments.getFirst();
			var payload = new EnvironmentUpdateRequest(
					env.getWaitTimer(),
					null,
					null
			);
			client.updateEnvironment(
					"ArloL",
					"terraform-github",
					env.name(),
					payload
			);
		}

		wm.stopRecording();
	}

	private static void clearDirectory(Path dir) {
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
