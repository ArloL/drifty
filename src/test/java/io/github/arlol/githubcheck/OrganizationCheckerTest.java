package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * Only {@code /orgs/my-org} is stubbed, and the fixture manages only
 * {@code org_settings}. A group whose request escaped its guard in
 * {@code fetchState} would hit an unstubbed path and fail these tests, which is
 * what makes them a guard test as well as a checker test.
 */
@WireMockTest
class OrganizationCheckerTest {

	private OrganizationChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		checker = new OrganizationChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				false,
				Map.of(),
				new DriftyState()
		);
	}

	private static Drifty.Organization onlySettings() {
		return Desired.organization()
				.withManaged(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_SETTINGS)
						)
				);
	}

	private static void stubOrg(String description) {
		stubFor(get(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("""
				{
				  "login": "my-org",
				  "description": %s,
				  "default_repository_permission": "read",
				  "default_repository_branch": "main",
				  "has_organization_projects": true,
				  "has_repository_projects": true,
				  "members_can_create_repositories": true,
				  "members_can_create_public_repositories": true,
				  "members_can_create_private_repositories": true,
				  "members_can_create_pages": true,
				  "members_can_create_public_pages": true,
				  "members_can_create_private_pages": true,
				  "members_can_delete_repositories": true,
				  "members_can_change_repo_visibility": true,
				  "members_can_invite_outside_collaborators": true,
				  "members_can_create_teams": true,
				  "members_can_view_dependency_insights": true
				}
				""".formatted(description))));
	}

	@Test
	void matchingSettingsReportOk() {
		stubOrg("null");

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.OK);
		assertThat(entry.unmanaged()).containsExactlyInAnyOrder(
				"org_actions_permissions",
				"org_workflow_permissions",
				"org_action_secrets"
		);
	}

	@Test
	void driftedDescriptionIsReported() {
		stubOrg("\"stale\"");

		CheckResult.Entry entry = checker.check(
				"my-org",
				onlySettings().withDescription("wanted"),
				List.of()
		);

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.diffs()).singleElement()
				.asString()
				.startsWith("org_settings.description:");
		assertThat(entry.fixPreview()).containsExactly("org_settings");
	}

	@Test
	void unknownOrganizationIsMissing() {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org"))
						.willReturn(aResponse().withStatus(404))
		);

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.MISSING);
	}

}
