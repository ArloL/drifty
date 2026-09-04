package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.testsupport.Actual;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrgSettingsDriftGroupTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void noDriftWhenEverythingMatches() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization(),
				Actual.organization(),
				null,
				"my-org"
		);

		assertThat(group.detect().getFirst().items()).isEmpty();
	}

	@Test
	void detectsWritableDrift() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization().withDescription("wanted"),
				Actual.organization(),
				null,
				"my-org"
		);

		List<DriftItem> items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).extracting(DriftItem::path)
				.containsExactly("org_settings.description");
	}

	@Test
	void checkOnlySettingIsReportedAndNotWritten() {
		var group = new OrgSettingsDriftGroup(
				Desired.organization().withMembersCanDeleteRepositories(false),
				Actual.organization(),
				null,
				"my-org"
		);

		FixResult result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).singleElement().satisfies(unfixed -> {
			assertThat(unfixed.item().path())
					.isEqualTo("org_settings.members_can_delete_repositories");
			assertThat(unfixed.reason())
					.contains("cannot be changed through the API");
		});
	}

	/**
	 * Every writable row writes the setting it compared, checked one row at a
	 * time and derived from the table itself, so a row added to it is a case
	 * added here.
	 * <p>
	 * The thirty rows pair a comparison with a builder call by hand. A row that
	 * compared {@code members_can_create_public_pages} and wrote
	 * {@code members_can_create_private_pages} passed every other test in this
	 * suite and would quietly write the wrong setting to a live organization.
	 * <p>
	 * Everything drifts here and the PATCH is refused, which is what makes each
	 * field arrive as its own request: the group re-sends them individually to
	 * find out which one GitHub actually rejected. Each of those requests is
	 * one wire field, and it has to be the field the drift item it belongs to
	 * named, carrying the value that item wanted.
	 */
	@Test
	void everyWritableSettingWritesTheFieldItCompared(WireMockRuntimeInfo wm)
			throws Exception {
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org")).willReturn(
						aResponse().withStatus(422)
								.withBody("{\"message\": \"nope\"}")
				)
		);
		var group = new OrgSettingsDriftGroup(
				Desired.organization(),
				Actual.driftedOrganization(),
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				"my-org"
		);

		FixResult result = group.detect().getFirst().fix().execute();

		// A row with no write is reported with its own reason and never sent,
		// so the ones GitHub refused are exactly the writable ones.
		Map<String, JsonNode> wanted = new LinkedHashMap<>();
		for (FixResult.Unfixed unfixed : result.unfixedItems()) {
			if (unfixed.reason().contains("HTTP 422")) {
				var mismatch = (DriftItem.FieldMismatch) unfixed.item();
				wanted.put(
						mismatch.path().substring("org_settings.".length()),
						MAPPER.valueToTree(mismatch.wanted())
				);
			}
		}
		assertThat(wanted).as("writable settings").hasSize(20);

		List<LoggedRequest> requests = findAll(
				patchRequestedFor(urlPathEqualTo("/orgs/my-org"))
		);
		assertThat(requests).hasSize(wanted.size() + 1);

		ObjectNode batch = MAPPER.createObjectNode();
		wanted.forEach(batch::set);
		assertThat(body(requests.getFirst()))
				.as("the first request carries every drifted writable setting")
				.isEqualTo(batch);

		Map<String, JsonNode> sent = new LinkedHashMap<>();
		for (LoggedRequest request : requests.subList(1, requests.size())) {
			ObjectNode body = body(request);
			assertThat(body.size()).as("fields in %s", body).isEqualTo(1);
			var field = body.fields().next();
			sent.put(field.getKey(), field.getValue());
		}
		assertThat(sent).isEqualTo(wanted);
	}

	private static ObjectNode body(LoggedRequest request) throws Exception {
		return (ObjectNode) MAPPER.readTree(request.getBodyAsString());
	}

}
