package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
 * The organization twin of {@link RepositoryCheckerFixTest}: what a
 * {@code --fix} run writes to {@code PATCH /orgs/{org}} and how it reports what
 * GitHub refused.
 */
@WireMockTest
class OrganizationCheckerFixTest {

	private OrganizationChecker fixer;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		fixer = new OrganizationChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				true,
				Map.of(),
				new DriftyState()
		);
	}

	@Test
	void writesTheDriftedSettingsAndReportsThemFixed() {
		stubOrg();
		stubFor(patch(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("{}")));

		CheckResult.Entry entry = fix(
				onlySettings().withDescription("wanted").withLocation("Berlin")
		);

		assertThat(entry.status()).isEqualTo(CheckResult.Status.OK);
		assertThat(entry.fixReports())
				.extracting(
						CheckResult.FixReport::path,
						CheckResult.FixReport::fixed
				)
				.containsExactlyInAnyOrder(
						tuple("org_settings.description", true),
						tuple("org_settings.location", true)
				);
		verify(
				1,
				patchRequestedFor(urlPathEqualTo("/orgs/my-org"))
						.withRequestBody(equalToJson("""
								{"description": "wanted", "location": "Berlin"}
								"""))
		);
	}

	/**
	 * GitHub applies what it accepts and rejects the rest, so a 422 on a
	 * multi-field PATCH says nothing about which field failed. A second pass
	 * sends each field on its own: the ones GitHub takes are fixed, and only
	 * the one it refuses is reported unfixed, with its own error as the reason.
	 * Reporting the whole request as failed instead — the shape the repository
	 * side once had — told the operator every setting was still drifted after
	 * GitHub had already changed most of them.
	 */
	@Test
	void aPatchRejectingOneFieldFixesTheRestAndNamesIt() {
		stubOrg();
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org")).atPriority(5)
						.willReturn(
								aResponse().withStatus(422)
										.withBody(
												"{\"message\": \"members_can_create_internal_repositories is only available to Enterprise organizations\"}"
										)
						)
		);
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org")).atPriority(1)
						.withRequestBody(equalToJson("""
								{"description": "wanted"}
								"""))
						.willReturn(okJson("{}"))
		);

		CheckResult.Entry entry = fix(
				onlySettings().withDescription("wanted")
						.withMembersCanCreateInternalRepositories(true)
		);

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.fixReports())
				.extracting(
						CheckResult.FixReport::path,
						CheckResult.FixReport::fixed
				)
				.containsExactlyInAnyOrder(
						tuple("org_settings.description", true),
						tuple(
								"org_settings.members_can_create_internal_repositories",
								false
						)
				);
		assertThat(entry.fixReports()).filteredOn(report -> !report.fixed())
				.singleElement()
				.extracting(CheckResult.FixReport::reason)
				.asString()
				.contains("422");

		verify(
				patchRequestedFor(urlPathEqualTo("/orgs/my-org"))
						.withRequestBody(
								equalToJson(
										"""
												{"description": "wanted", "members_can_create_internal_repositories": true}
												"""
								)
						)
		);
		verify(
				patchRequestedFor(urlPathEqualTo("/orgs/my-org"))
						.withRequestBody(equalToJson("""
								{"description": "wanted"}
								"""))
		);
		verify(
				patchRequestedFor(
						urlPathEqualTo("/orgs/my-org")
				).withRequestBody(equalToJson("""
						{"members_can_create_internal_repositories": true}
						"""))
		);
		verify(3, patchRequestedFor(urlPathEqualTo("/orgs/my-org")));
	}

	/**
	 * One drifted field is already its own attribution, so a failure needs no
	 * isolation pass — and must not cost a second request.
	 */
	@Test
	void aSingleFieldPatchFailureIsNotRetried() {
		stubOrg();
		stubFor(
				patch(urlPathEqualTo("/orgs/my-org")).willReturn(
						aResponse().withStatus(422)
								.withBody("{\"message\": \"nope\"}")
				)
		);

		CheckResult.Entry entry = fix(onlySettings().withDescription("wanted"));

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.fixReports()).singleElement()
				.extracting(
						CheckResult.FixReport::path,
						CheckResult.FixReport::fixed
				)
				.containsExactly("org_settings.description", false);
		verify(1, patchRequestedFor(urlPathEqualTo("/orgs/my-org")));
	}

	private CheckResult.Entry fix(Drifty.Organization desired) {
		return fixer.check("my-org", desired, List.of());
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

	/** GitHub's defaults, so only what a test asks for drifts. */
	private static void stubOrg() {
		stubFor(get(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("""
				{
				  "login": "my-org",
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
				""")));
	}

}
