package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrgActionVariablesDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	@Test
	void valueAndVisibilityDriftShareOneFix() {
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org/actions/variables/REGION"))
						.willReturn(aResponse().withStatus(204))
		);
		var group = new OrgActionVariablesDriftGroup(
				Map.of(
						"REGION",
						Desired.orgVariable("eu")
								.withVisibility(Drifty.SecretVisibility.ALL)
				),
				List.of(
						new ActualOrgVariable(
								"REGION",
								"us",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				Map.of(),
				client,
				"my-org"
		);

		var fixes = group.detect();

		assertThat(fixes).singleElement()
				.extracting(DriftFix::items)
				.asInstanceOf(
						org.assertj.core.api.InstanceOfAssertFactories
								.list(DriftItem.class)
				)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_action_variables.REGION.value",
						"org_action_variables.REGION.visibility"
				);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(
						urlPathEqualTo("/orgs/my-org/actions/variables/REGION")
				).withRequestBody(equalToJson("""
						{"name": "REGION", "value": "eu", "visibility": "all"}
						"""))
		);
	}

	@Test
	void selectedRepositoriesAreResolvedToIdsOnCreate() {
		stubFor(
				post(urlPathEqualTo("/orgs/my-org/actions/variables"))
						.willReturn(aResponse().withStatus(201))
		);
		var group = new OrgActionVariablesDriftGroup(
				Map.of(
						"REGION",
						Desired.orgVariable("eu")
								.withVisibility(
										Drifty.SecretVisibility.SELECTED
								)
								.withSelectedRepositories(List.of("api"))
				),
				List.of(),
				Map.of("api", 7L),
				client,
				"my-org"
		);

		group.detect().getFirst().fix().execute();

		verify(
				postRequestedFor(
						urlPathEqualTo("/orgs/my-org/actions/variables")
				).withRequestBody(
						equalToJson(
								"""
										{"name": "REGION", "value": "eu", "visibility": "selected", "selected_repository_ids": [7]}
										"""
						)
				)
		);
	}

	@Test
	void selectedRepositoriesAreComparedOnlyUnderSelected() {
		var matching = new OrgActionVariablesDriftGroup(
				Map.of("REGION", Desired.orgVariable("eu")),
				List.of(
						new ActualOrgVariable(
								"REGION",
								"eu",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				Map.of(),
				null,
				"my-org"
		);
		assertThat(matching.detect()).flatExtracting(DriftFix::items).isEmpty();

		var selected = new OrgActionVariablesDriftGroup(
				Map.of(
						"REGION",
						Desired.orgVariable("eu")
								.withVisibility(
										Drifty.SecretVisibility.SELECTED
								)
								.withSelectedRepositories(List.of("api"))
				),
				List.of(
						new ActualOrgVariable(
								"REGION",
								"eu",
								SecretVisibility.SELECTED,
								List.of("web")
						)
				),
				Map.of(),
				null,
				"my-org"
		);
		assertThat(selected.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_action_variables.REGION.selected_repositories"
				);
	}

	@Test
	void extraVariablesAreReportedNotDeleted() {
		var group = new OrgActionVariablesDriftGroup(
				Map.of(),
				List.of(
						new ActualOrgVariable(
								"STRAY",
								"x",
								SecretVisibility.ALL,
								List.of()
						)
				),
				Map.of(),
				null,
				"my-org"
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("does not delete variables");
	}

}
