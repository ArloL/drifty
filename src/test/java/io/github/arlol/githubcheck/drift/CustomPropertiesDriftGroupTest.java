package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualCustomPropertyValue;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

@WireMockTest
class CustomPropertiesDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private CustomPropertiesDriftGroup group(
			Map<String, String> desired,
			Map<String, List<String>> desiredMulti,
			List<ActualCustomPropertyValue> actual
	) {
		return group(desired, desiredMulti, actual, true);
	}

	private CustomPropertiesDriftGroup group(
			Map<String, String> desired,
			Map<String, List<String>> desiredMulti,
			List<ActualCustomPropertyValue> actual,
			boolean organizationOwned
	) {
		return new CustomPropertiesDriftGroup(
				desired,
				desiredMulti,
				actual,
				organizationOwned,
				client,
				new RepoRef("owner", "repo")
		);
	}

	@Test
	void noDrift_whenNamedPropertiesMatchAndOthersAreIgnored() {
		var group = group(
				Map.of("tier", "gold"),
				Map.of("tags", List.of("a", "b")),
				List.of(
						new ActualCustomPropertyValue(
								"tier",
								"gold",
								List.of()
						),
						new ActualCustomPropertyValue(
								"tags",
								null,
								List.of("b", "a")
						),
						new ActualCustomPropertyValue(
								"unmentioned",
								"x",
								List.of()
						)
				)
		);

		assertThat(group.detect()).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.GroupName.CUSTOM_PROPERTIES);
	}

	@Test
	void driftedAndUnsetProperties_arePatchedInOneRequest() {
		stubFor(
				patch(urlPathEqualTo("/repos/owner/repo/properties/values"))
						.willReturn(aResponse().withStatus(204))
		);
		var group = group(
				Map.of("tier", "gold", "same", "yes"),
				Map.of("tags", List.of("a")),
				List.of(
						new ActualCustomPropertyValue(
								"tier",
								"silver",
								List.of()
						),
						new ActualCustomPropertyValue("same", "yes", List.of())
				)
		);

		var fixes = group.detect();

		assertThat(fixes).singleElement()
				.extracting(DriftFix::items)
				.asInstanceOf(
						org.assertj.core.api.InstanceOfAssertFactories
								.list(DriftItem.class)
				)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"custom_properties.tier",
						"custom_properties.tags"
				);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(
						urlPathEqualTo("/repos/owner/repo/properties/values")
				).withRequestBody(equalToJson("""
						{"properties": [
						  {"property_name": "tier", "value": "gold"},
						  {"property_name": "tags", "value": ["a"]}
						]}
						""", true, false))
		);
	}

	@Test
	void unsetStringProperty_isReportedAgainstNull() {
		var items = group(Map.of("tier", "gold"), Map.of(), List.of()).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).singleElement().satisfies(item -> {
			var mismatch = (DriftItem.FieldMismatch) item;
			assertThat(mismatch.wanted()).isEqualTo("gold");
			assertThat(mismatch.got()).isNull();
		});
	}

	/**
	 * A personal account has no custom property schema, so the PATCH 404s the
	 * same way the read does. The properties the config names are still
	 * reported — leaving them out would hide a config that cannot be satisfied
	 * — but the fix says why it did not run instead of sending the request.
	 */
	@Test
	void personalAccount_reportsThePropertiesAndSendsNoRequest() {
		var group = group(
				Map.of("tier", "gold"),
				Map.of("tags", List.of("a", "b")),
				List.of(),
				false
		);

		var fixes = group.detect();

		assertThat(fixes).singleElement()
				.extracting(DriftFix::items, list(DriftItem.class))
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"custom_properties.tier",
						"custom_properties.tags"
				);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).hasSize(2)
				.extracting(FixResult.Unfixed::reason)
				.allSatisfy(
						reason -> assertThat(reason)
								.contains("organization-owned")
				);
		verify(0, patchRequestedFor(urlPathEqualTo("/repos/owner/repo")));
		verify(
				0,
				patchRequestedFor(
						urlPathEqualTo("/repos/owner/repo/properties/values")
				)
		);
	}

}
