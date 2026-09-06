package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrgRunnerGroupsDriftGroupTest {

	private static final String BASE = "/orgs/my-org/actions/runner-groups";

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static ActualRunnerGroup group(
			long id,
			String name,
			String visibility,
			boolean isDefault,
			List<String> repositories
	) {
		return new ActualRunnerGroup(
				id,
				name,
				visibility,
				isDefault,
				false,
				false,
				Set.of(),
				repositories
		);
	}

	private OrgRunnerGroupsDriftGroup driftGroup(
			Map<String, Drifty.RunnerGroup> desired,
			List<ActualRunnerGroup> actual
	) {
		return new OrgRunnerGroupsDriftGroup(
				desired,
				actual,
				Map.of("one", 10L, "two", 11L),
				client,
				"my-org"
		);
	}

	@Test
	void noDrift_whenGroupsMatch_andTheDefaultIsNeverExtra() {
		var group = driftGroup(
				Map.of("gpu", Desired.runnerGroup()),
				List.of(
						group(1, "Default", "all", true, List.of()),
						group(2, "gpu", "all", false, List.of())
				)
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items).isEmpty();
		assertThat(group.name())
				.isEqualTo(Drifty.OrgGroupName.ORG_RUNNER_GROUPS);
	}

	@Test
	void settingsAndSelectionAreSeparateFixes() {
		stubFor(
				patch(urlPathEqualTo(BASE + "/2"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);
		stubFor(
				put(urlPathEqualTo(BASE + "/2/repositories"))
						.willReturn(aResponse().withStatus(204))
		);
		var wanted = Desired.runnerGroup()
				.withVisibility(Drifty.RunnerGroupVisibility.SELECTED)
				.withSelectedRepositories(List.of("one", "two"))
				.withRestrictedToWorkflows(true)
				.withSelectedWorkflows(
						List.of(
								"my-org/one/.github/workflows/ci.yml@refs/heads/main"
						)
				);

		var fixes = driftGroup(
				Map.of("gpu", wanted),
				List.of(group(2, "gpu", "all", false, List.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_runner_groups.gpu.visibility",
						"org_runner_groups.gpu.restricted_to_workflows",
						"org_runner_groups.gpu.selected_workflows",
						"org_runner_groups.gpu.selected_repositories"
				);
		for (var fix : fixes) {
			assertThat(fix.fix().execute().unfixedItems()).isEmpty();
		}
		verify(
				patchRequestedFor(urlPathEqualTo(BASE + "/2")).withRequestBody(
						equalToJson(
								"""
										{"name": "gpu", "visibility": "selected",
										 "allows_public_repositories": false, "restricted_to_workflows": true,
										 "selected_workflows": ["my-org/one/.github/workflows/ci.yml@refs/heads/main"]}
										"""
						)
				)
		);
		verify(
				putRequestedFor(
						urlPathEqualTo(BASE + "/2/repositories")
				).withRequestBody(
						equalToJson("{\"selected_repository_ids\": [10, 11]}")
				)
		);
	}

	@Test
	void missingGroup_isPostedWithItsRepositoryIds() {
		stubFor(
				post(urlPathEqualTo(BASE)).willReturn(
						aResponse().withStatus(201)
								.withBody("{\"id\": 3, \"name\": \"gpu\"}")
				)
		);
		var wanted = Desired.runnerGroup()
				.withVisibility(Drifty.RunnerGroupVisibility.SELECTED)
				.withSelectedRepositories(List.of("one"));

		var fixes = driftGroup(Map.of("gpu", wanted), List.of()).detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlPathEqualTo(BASE)).withRequestBody(
						equalToJson(
								"""
										{"name": "gpu", "visibility": "selected", "selected_repository_ids": [10],
										 "allows_public_repositories": false, "restricted_to_workflows": false,
										 "selected_workflows": []}
										"""
						)
				)
		);
	}

	@Test
	void unknownRepository_isReportedUnfixed() {
		var wanted = Desired.runnerGroup()
				.withVisibility(Drifty.RunnerGroupVisibility.SELECTED)
				.withSelectedRepositories(List.of("nope"));

		var fixes = driftGroup(Map.of("gpu", wanted), List.of()).detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("no repository nope");
	}

	@Test
	void extraGroup_isDeleted() {
		stubFor(
				delete(urlPathEqualTo(BASE + "/5"))
						.willReturn(aResponse().withStatus(204))
		);

		var fixes = driftGroup(
				Map.of(),
				List.of(group(5, "stray", "all", false, List.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(deleteRequestedFor(urlPathEqualTo(BASE + "/5")));
	}

}
