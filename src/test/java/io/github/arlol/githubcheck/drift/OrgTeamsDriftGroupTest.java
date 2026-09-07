package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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

import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrgTeamsDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static ActualTeam team(
			long id,
			String slug,
			String parent,
			Set<String> members,
			Set<String> maintainers
	) {
		return new ActualTeam(
				id,
				slug,
				slug,
				"",
				"closed",
				"notifications_enabled",
				parent,
				members,
				maintainers
		);
	}

	private OrgTeamsDriftGroup group(
			Map<String, Drifty.Team> desired,
			List<ActualTeam> actual
	) {
		return new OrgTeamsDriftGroup(desired, actual, client, "my-org");
	}

	@Test
	void noDrift_whenSettingsAndMembersMatch() {
		var group = group(
				Map.of(
						"core",
						Desired.team()
								.withMembers(List.of("alice"))
								.withMaintainers(List.of("bob"))
				),
				List.of(team(1, "core", null, Set.of("alice"), Set.of("bob")))
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.OrgGroupName.ORG_TEAMS);
	}

	@Test
	void settingsArePatchedWithTheParentResolvedFromTheListing() {
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org/teams/core"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);
		var wanted = Desired.team()
				.withName("Core Team")
				.withDescription("the core")
				.withPrivacy(Drifty.TeamPrivacy.SECRET)
				.withNotificationSetting(
						Drifty.TeamNotificationSetting.NOTIFICATIONS_DISABLED
				)
				.withParent("parents");

		var fixes = group(
				Map.of("core", wanted, "parents", Desired.team()),
				List.of(
						team(1, "core", null, Set.of(), Set.of()),
						team(2, "parents", null, Set.of(), Set.of())
				)
		).detect();

		var settings = fixes.stream()
				.filter(f -> !f.items().isEmpty())
				.toList();
		assertThat(settings).singleElement()
				.extracting(DriftFix::items)
				.asInstanceOf(
						org.assertj.core.api.InstanceOfAssertFactories
								.list(DriftItem.class)
				)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_teams.core.name",
						"org_teams.core.description",
						"org_teams.core.privacy",
						"org_teams.core.notification_setting",
						"org_teams.core.parent"
				);
		assertThat(settings.getFirst().fix().execute().unfixedItems())
				.isEmpty();
		verify(
				patchRequestedFor(urlPathEqualTo("/orgs/my-org/teams/core"))
						.withRequestBody(
								equalToJson(
										"""
												{"name": "Core Team", "description": "the core", "privacy": "secret",
												 "notification_setting": "notifications_disabled", "parent_team_id": 2}
												"""
								)
						)
		);
	}

	@Test
	void missingMembersArePutPerRole_andExtrasAreReported() {
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/teams/core/memberships/carol"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);
		var wanted = Desired.team()
				.withMembers(List.of("alice", "carol"))
				.withMaintainers(List.of("bob"));

		var fixes = group(
				Map.of("core", wanted),
				List.of(
						team(
								1,
								"core",
								null,
								Set.of("alice", "dave"),
								Set.of("bob")
						)
				)
		).detect();

		var active = fixes.stream().filter(f -> !f.items().isEmpty()).toList();
		assertThat(active).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_teams.core.members",
						"org_teams.core.members.dave"
				);
		var unfixed = active.stream()
				.flatMap(f -> f.fix().execute().unfixedItems().stream())
				.toList();
		assertThat(unfixed).singleElement()
				.extracting(u -> u.item().path())
				.isEqualTo("org_teams.core.members.dave");
		verify(
				putRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/teams/core/memberships/carol"
						)
				).withRequestBody(equalToJson("{\"role\": \"member\"}"))
		);
	}

	@Test
	void missingTeam_isCreatedWithItsNameThenGivenItsMembers() {
		stubFor(
				post(urlPathEqualTo("/orgs/my-org/teams")).willReturn(
						aResponse().withStatus(201)
								.withBody(
										"{\"id\": 9, \"name\": \"platform\", \"slug\": \"platform\"}"
								)
				)
		);
		stubFor(
				put(
						urlPathEqualTo(
								"/orgs/my-org/teams/platform/memberships/bob"
						)
				).willReturn(aResponse().withStatus(200).withBody("{}"))
		);

		var fixes = group(
				Map.of(
						"platform",
						Desired.team().withMaintainers(List.of("bob"))
				),
				List.of()
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlPathEqualTo("/orgs/my-org/teams"))
						.withRequestBody(
								equalToJson(
										"""
												{"name": "platform", "description": "", "privacy": "closed",
												 "notification_setting": "notifications_enabled"}
												"""
								)
						)
		);
		verify(
				putRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/teams/platform/memberships/bob"
						)
				).withRequestBody(equalToJson("{\"role\": \"maintainer\"}"))
		);
	}

	@Test
	void missingTeam_withAParent_sendsTheResolvedParentId() {
		stubFor(
				post(urlPathEqualTo("/orgs/my-org/teams")).willReturn(
						aResponse().withStatus(201)
								.withBody(
										"{\"id\": 9, \"name\": \"platform\", \"slug\": \"platform\"}"
								)
				)
		);

		var fixes = group(
				Map.of(
						"platform",
						Desired.team().withParent("parents"),
						"parents",
						Desired.team()
				),
				List.of(team(2, "parents", null, Set.of(), Set.of()))
		).detect();

		var missing = fixes.stream()
				.filter(
						f -> f.items()
								.stream()
								.anyMatch(
										i -> i instanceof DriftItem.SectionMissing
								)
				)
				.toList();
		assertThat(missing).singleElement()
				.extracting(f -> f.fix().execute().unfixedItems())
				.asInstanceOf(
						org.assertj.core.api.InstanceOfAssertFactories
								.list(FixResult.Unfixed.class)
				)
				.isEmpty();
		verify(
				postRequestedFor(urlPathEqualTo("/orgs/my-org/teams"))
						.withRequestBody(
								equalToJson(
										"""
												{"name": "platform", "description": "", "privacy": "closed",
												 "notification_setting": "notifications_enabled", "parent_team_id": 2}
												"""
								)
						)
		);
	}

	@Test
	void droppingTheParent_patchesAnExplicitNull() {
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org/teams/core"))
						.willReturn(aResponse().withStatus(200).withBody("{}"))
		);

		var fixes = group(
				Map.of("core", Desired.team()),
				List.of(team(1, "core", "parents", Set.of(), Set.of()))
		).detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(urlPathEqualTo("/orgs/my-org/teams/core"))
						.withRequestBody(
								equalToJson(
										"""
												{"name": "core", "description": "", "privacy": "closed",
												 "notification_setting": "notifications_enabled", "parent_team_id": null}
												"""
								)
						)
		);
	}

	@Test
	void extraTeam_isReportedAndNeverDeleted() {
		var fixes = group(
				Map.of(),
				List.of(team(1, "stray", null, Set.of(), Set.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("does not delete teams");
	}

}
