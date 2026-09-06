package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class EnvironmentVariablesDriftGroupTest {

	@Test
	void pathsCarryTheEnvironmentAndMissingOnesAreCreatedThere(
			WireMockRuntimeInfo wm
	) {
		stubFor(
				post(
						urlPathEqualTo(
								"/repos/owner/repo/environments/production/variables"
						)
				).willReturn(aResponse().withStatus(201))
		);
		var group = new EnvironmentVariablesDriftGroup(
				Map.of(
						"production",
						Desired.environment()
								.withVariables(
										Map.of("TIER", "prod", "REGION", "eu")
								)
				),
				Map.of(
						"production",
						List.of(
								new ActualVariable("REGION", "eu"),
								new ActualVariable("STRAY", "x")
						)
				),
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				new RepoRef("owner", "repo")
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"environment_variables.production.variables.TIER",
						"environment_variables.production.variables.STRAY"
				);
		fixes.stream()
				.filter(f -> f.items().getFirst().path().endsWith("TIER"))
				.findFirst()
				.orElseThrow()
				.fix()
				.execute();
		verify(
				postRequestedFor(
						urlPathEqualTo(
								"/repos/owner/repo/environments/production/variables"
						)
				).withRequestBody(
						equalToJson("{\"name\": \"TIER\", \"value\": \"prod\"}")
				)
		);
	}

	@Test
	void anEnvironmentGitHubLacksReportsItsVariablesMissing() {
		var group = new EnvironmentVariablesDriftGroup(
				Map.of(
						"production",
						Desired.environment()
								.withVariables(Map.of("TIER", "prod"))
				),
				Map.of(),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class);
	}

}
