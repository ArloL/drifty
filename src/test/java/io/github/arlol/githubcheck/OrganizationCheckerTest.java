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

import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * Each test stubs only the endpoints the group it manages is allowed to read. A
 * group whose request escaped its guard in {@code fetchState} would hit an
 * unstubbed path and fail these tests, which is what makes them a guard test as
 * well as a checker test.
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

	/**
	 * The repositories of the private secret are never asked for: that request
	 * is not stubbed, so a fetch that sent it would fail here.
	 */
	@Test
	void secretRepositoriesAreReadOnlyForSelectedSecrets() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets")).willReturn(
						okJson(
								"""
										{
										  "total_count": 2,
										  "secrets": [
										    {"name": "PAT", "updated_at": "t1", "visibility": "private"},
										    {"name": "SHARED", "updated_at": "t2", "visibility": "selected"}
										  ]
										}
										"""
						)
				)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/secrets/SHARED/repositories"
						)
				).willReturn(okJson("""
						{
						  "total_count": 1,
						  "repositories": [
						    {"id": 1, "name": "one", "archived": false}
						  ]
						}
						"""))
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_ACTION_SECRETS)
						)
				)
		);

		assertThat(state.actionSecrets()).containsExactly(
				new ActualOrgSecret(
						"PAT",
						"t1",
						SecretVisibility.PRIVATE,
						List.of()
				),
				new ActualOrgSecret(
						"SHARED",
						"t2",
						SecretVisibility.SELECTED,
						List.of("one")
				)
		);
	}

	/**
	 * {@code Would fix:} is what an operator reads to decide whether to run
	 * {@code --fix}, so it must name the groups that drifted and no others.
	 * Three of the four groups here match GitHub exactly, and all four return a
	 * fix object whether or not they found drift — keying the preview on that
	 * object rather than on its items previewed
	 * {@code org_settings, org_actions_permissions, org_workflow_permissions}
	 * for this organization.
	 */
	@Test
	void fixPreviewNamesOnlyTheGroupThatDrifted() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions"))
						.willReturn(okJson("""
								{
								  "enabled_repositories": "all",
								  "allowed_actions": "all"
								}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions/workflow"))
						.willReturn(okJson("""
								{
								  "default_workflow_permissions": "read",
								  "can_approve_pull_request_reviews": true
								}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets"))
						.willReturn(okJson("{\"secrets\": []}"))
		);

		CheckResult.Entry entry = checker
				.check("my-org", Desired.organization(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.diffs()).singleElement()
				.asString()
				.startsWith(
						"org_workflow_permissions.default_workflow_permissions:"
				);
		assertThat(entry.fixPreview())
				.containsExactly("org_workflow_permissions");
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
