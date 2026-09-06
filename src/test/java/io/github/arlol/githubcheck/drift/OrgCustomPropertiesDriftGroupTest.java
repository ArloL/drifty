package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
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

import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrgCustomPropertiesDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private OrgCustomPropertiesDriftGroup group(
			Map<String, Drifty.CustomProperty> desired,
			List<ActualCustomProperty> actual
	) {
		return new OrgCustomPropertiesDriftGroup(
				desired,
				actual,
				client,
				"my-org"
		);
	}

	private static ActualCustomProperty stringProperty(String name) {
		return new ActualCustomProperty(
				name,
				"string",
				false,
				null,
				List.of(),
				"",
				List.of(),
				"org_actors"
		);
	}

	@Test
	void noDrift_whenDefinitionMatches() {
		var group = group(
				Map.of(
						"tier",
						Desired.customProperty(
								Drifty.CustomPropertyValueType.STRING
						)
				),
				List.of(stringProperty("tier"))
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items).isEmpty();
		assertThat(group.name())
				.isEqualTo(Drifty.OrgGroupName.ORG_CUSTOM_PROPERTIES);
	}

	@Test
	void everyFieldIsCompared_andMultiSelectDefaultsAgainstTheList() {
		var wanted = Desired
				.customProperty(Drifty.CustomPropertyValueType.MULTI_SELECT)
				.withRequired(true)
				.withDefaultValues(List.of("a"))
				.withDescription("tags")
				.withAllowedValues(List.of("a", "b"))
				.withValuesEditableBy(
						Drifty.CustomPropertyEditableBy.ORG_AND_REPO_ACTORS
				);

		var items = group(
				Map.of("tags", wanted),
				List.of(stringProperty("tags"))
		).detect().stream().flatMap(f -> f.items().stream()).toList();

		assertThat(items).extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_custom_properties.tags.value_type",
						"org_custom_properties.tags.required",
						"org_custom_properties.tags.default_value",
						"org_custom_properties.tags.description",
						"org_custom_properties.tags.allowed_values",
						"org_custom_properties.tags.values_editable_by"
				);
	}

	@Test
	void missingDefinition_isPutWithAllowedValuesOnlyForSelectTypes() {
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/properties/schema/tier"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/properties/schema/note"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);
		var group = group(
				Map.of(
						"tier",
						Desired.customProperty(
								Drifty.CustomPropertyValueType.SINGLE_SELECT
						)
								.withDefaultValue("gold")
								.withAllowedValues(List.of("gold", "silver")),
						"note",
						Desired.customProperty(
								Drifty.CustomPropertyValueType.STRING
						)
				),
				List.of()
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.allMatch(item -> item instanceof DriftItem.SectionMissing);
		for (var fix : fixes) {
			assertThat(fix.fix().execute().unfixedItems()).isEmpty();
		}
		verify(
				putRequestedFor(
						urlPathEqualTo("/orgs/my-org/properties/schema/tier")
				).withRequestBody(equalToJson("""
						{"value_type": "single_select", "required": false,
						 "default_value": "gold", "description": null,
						 "allowed_values": ["gold", "silver"],
						 "values_editable_by": "org_actors"}
						"""))
		);
		verify(
				putRequestedFor(
						urlPathEqualTo("/orgs/my-org/properties/schema/note")
				).withRequestBody(equalToJson("""
						{"value_type": "string", "required": false,
						 "default_value": null, "description": null,
						 "values_editable_by": "org_actors"}
						"""))
		);
	}

	@Test
	void extraDefinition_isReportedAndNeverDeleted() {
		var fixes = group(Map.of(), List.of(stringProperty("stray"))).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("does not delete custom properties");
	}

}
