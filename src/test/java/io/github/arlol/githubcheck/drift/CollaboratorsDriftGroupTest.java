package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

@WireMockTest
class CollaboratorsDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private CollaboratorsDriftGroup group(
			Map<String, Drifty.CollaboratorPermission> users,
			Map<String, Drifty.CollaboratorPermission> teams,
			ActualCollaborators actual,
			boolean organizationOwned
	) {
		return new CollaboratorsDriftGroup(
				users,
				teams,
				actual,
				organizationOwned,
				client,
				new RepoRef("owner", "repo")
		);
	}

	@Test
	void noDrift_whenPermissionsMatch() {
		var group = group(
				Map.of("alice", Drifty.CollaboratorPermission.PUSH),
				Map.of("core", Drifty.CollaboratorPermission.MAINTAIN),
				new ActualCollaborators(
						Map.of("alice", "push"),
						Map.of("core", "maintain")
				),
				true
		);

		assertThat(group.detect()).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.GroupName.COLLABORATORS);
	}

	@Test
	void missingAndDriftedCollaborators_arePutWithThePermission() {
		stubFor(
				put(urlPathEqualTo("/repos/owner/repo/collaborators/alice"))
						.willReturn(aResponse().withStatus(204))
		);
		stubFor(
				put(urlPathEqualTo("/repos/owner/repo/collaborators/bob"))
						.willReturn(aResponse().withStatus(201))
		);
		var group = group(
				Map.of(
						"alice",
						Drifty.CollaboratorPermission.ADMIN,
						"bob",
						Drifty.CollaboratorPermission.PULL
				),
				Map.of(),
				new ActualCollaborators(Map.of("alice", "push"), Map.of()),
				false
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"collaborators.alice",
						"collaborators.bob"
				);
		for (var fix : fixes) {
			assertThat(fix.fix().execute().unfixedItems()).isEmpty();
		}
		verify(
				putRequestedFor(
						urlPathEqualTo("/repos/owner/repo/collaborators/alice")
				).withRequestBody(equalToJson("{\"permission\": \"admin\"}"))
		);
		verify(
				putRequestedFor(
						urlPathEqualTo("/repos/owner/repo/collaborators/bob")
				).withRequestBody(equalToJson("{\"permission\": \"pull\"}"))
		);
	}

	@Test
	void teamAccess_isWrittenThroughTheOrganizationEndpoint() {
		stubFor(
				put(urlPathEqualTo("/orgs/owner/teams/core/repos/owner/repo"))
						.willReturn(aResponse().withStatus(204))
		);
		var group = group(
				Map.of(),
				Map.of("core", Drifty.CollaboratorPermission.PUSH),
				new ActualCollaborators(Map.of(), Map.of()),
				true
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.extracting(DriftItem::path)
				.isEqualTo("collaborators.teams.core");
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				putRequestedFor(
						urlPathEqualTo(
								"/orgs/owner/teams/core/repos/owner/repo"
						)
				).withRequestBody(equalToJson("{\"permission\": \"push\"}"))
		);
	}

	@Test
	void teamAccessOnAPersonalRepository_isAConfigError() {
		var group = group(
				Map.of(),
				Map.of("core", Drifty.CollaboratorPermission.PUSH),
				new ActualCollaborators(Map.of(), Map.of()),
				false
		);

		var fixes = group.detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("personal account");
	}

	@Test
	void extras_areReportedAndNeverRemoved() {
		var group = group(
				Map.of(),
				Map.of(),
				new ActualCollaborators(
						Map.of("stray", "pull"),
						Map.of("others", "pull")
				),
				true
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.allMatch(item -> item instanceof DriftItem.SectionExtra)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"collaborators.stray",
						"collaborators.teams.others"
				);
		for (var fix : fixes) {
			assertThat(fix.fix().execute().unfixedItems()).hasSize(1);
		}
	}

}
