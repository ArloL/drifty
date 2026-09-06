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

import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

@WireMockTest
class ActionVariablesDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private ActionVariablesDriftGroup group(
			Map<String, String> desired,
			List<ActualVariable> actual
	) {
		return new ActionVariablesDriftGroup(
				desired,
				actual,
				client,
				new RepoRef("owner", "repo")
		);
	}

	@Test
	void noDriftWhenValuesMatch() {
		var group = group(
				Map.of("REGION", "eu"),
				List.of(new ActualVariable("REGION", "eu"))
		);

		assertThat(group.detect()).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.GroupName.ACTION_VARIABLES);
	}

	@Test
	void missingVariableIsCreated() {
		stubFor(
				post(urlPathEqualTo("/repos/owner/repo/actions/variables"))
						.willReturn(aResponse().withStatus(201))
		);
		var group = group(Map.of("REGION", "eu"), List.of());

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class)
				.extracting(DriftItem::path)
				.isEqualTo("action_variables.REGION");
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(
						urlPathEqualTo("/repos/owner/repo/actions/variables")
				).withRequestBody(
						equalToJson("{\"name\": \"REGION\", \"value\": \"eu\"}")
				)
		);
	}

	@Test
	void changedValueIsPatched() {
		stubFor(
				patch(
						urlPathEqualTo(
								"/repos/owner/repo/actions/variables/REGION"
						)
				).willReturn(aResponse().withStatus(204))
		);
		var group = group(
				Map.of("REGION", "eu"),
				List.of(new ActualVariable("REGION", "us"))
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.satisfies(item -> {
					var mismatch = (DriftItem.FieldMismatch) item;
					assertThat(mismatch.path())
							.isEqualTo("action_variables.REGION");
					assertThat(mismatch.wanted()).isEqualTo("eu");
					assertThat(mismatch.got()).isEqualTo("us");
				});
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(
						urlPathEqualTo(
								"/repos/owner/repo/actions/variables/REGION"
						)
				).withRequestBody(
						equalToJson("{\"name\": \"REGION\", \"value\": \"eu\"}")
				)
		);
	}

	@Test
	void extraVariableIsReportedAndNotDeleted() {
		var group = group(Map.of(), List.of(new ActualVariable("STRAY", "x")));

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
