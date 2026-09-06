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

import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;

@WireMockTest
class OrgMembersDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private OrgMembersDriftGroup group(
			Map<String, Drifty.OrgRole> desired,
			List<ActualOrgMember> actual
	) {
		return new OrgMembersDriftGroup(desired, actual, client, "my-org");
	}

	@Test
	void noDrift_whenRolesMatch() {
		var group = group(
				Map.of("alice", Drifty.OrgRole.ADMIN),
				List.of(new ActualOrgMember("alice", "admin"))
		);

		assertThat(group.detect()).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.OrgGroupName.ORG_MEMBERS);
	}

	@Test
	void missingAndDriftedMembers_arePutWithTheRole() {
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/memberships/alice"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/memberships/bob"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);

		var fixes = group(
				Map.of(
						"alice",
						Drifty.OrgRole.ADMIN,
						"bob",
						Drifty.OrgRole.MEMBER
				),
				List.of(new ActualOrgMember("alice", "member"))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_members.alice",
						"org_members.bob"
				);
		for (var fix : fixes) {
			assertThat(fix.fix().execute().unfixedItems()).isEmpty();
		}
		verify(
				putRequestedFor(
						urlPathEqualTo("/orgs/my-org/memberships/alice")
				).withRequestBody(equalToJson("{\"role\": \"admin\"}"))
		);
		verify(
				putRequestedFor(urlPathEqualTo("/orgs/my-org/memberships/bob"))
						.withRequestBody(equalToJson("{\"role\": \"member\"}"))
		);
	}

	@Test
	void extraMember_isReportedAndNeverRemoved() {
		var fixes = group(
				Map.of(),
				List.of(new ActualOrgMember("stray", "member"))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("does not remove members");
	}

}
