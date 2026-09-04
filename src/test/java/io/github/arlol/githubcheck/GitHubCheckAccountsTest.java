package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
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
 * Covers {@link GitHubCheck#check}, the loop over the accounts the config
 * declares. Its job beyond dispatching is containment: one account's failure
 * belongs in that account's report line, not in a stack trace out of
 * {@code main} that leaves every other account unchecked.
 */
@WireMockTest
class GitHubCheckAccountsTest {

	private GitHubClient client;
	private OrganizationChecker orgChecker;
	private RepositoryChecker repoChecker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		orgChecker = new OrganizationChecker(
				client,
				false,
				Map.of(),
				new DriftyState()
		);
		repoChecker = new RepositoryChecker(client, false);
	}

	/**
	 * A 403 or 500 on the listing is what a token scoped to some of the
	 * declared accounts meets; only the 404 was ever handled. Thrown from here
	 * it took the whole run down: no report at all, and an exit code that reads
	 * as "drift detected".
	 */
	@Test
	void aFailedOrgListingIsThatOrgsErrorAndTheRunGoesOn() throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/broken/repos")).willReturn(
						aResponse().withStatus(500).withBody("boom")
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/healthy/repos"))
						.willReturn(okJson("[]"))
		);
		stubOrgSettings("healthy");

		CheckResult result = GitHubCheck
				.check(
						config(
								organizations(
										"broken",
										onlySettings().withRepositories(
												List.of(
														Desired.repository(
																"one"
														)
												)
										),
										"healthy",
										onlySettings()
								),
								Map.of()
						),
						client,
						orgChecker,
						repoChecker
				);

		assertThat(result.orgs())
				.extracting(CheckResult.Entry::name, CheckResult.Entry::status)
				.containsExactlyInAnyOrder(
						tuple("broken", CheckResult.Status.ERROR),
						tuple("healthy", CheckResult.Status.OK)
				);
		assertThat(result.orgs())
				.filteredOn(entry -> "broken".equals(entry.name()))
				.singleElement()
				.extracting(CheckResult.Entry::error)
				.asString()
				.contains("500");
		// The repositories under it were never listed, so they are errors too:
		// MISSING would claim GitHub answered and did not have them.
		assertThat(result.repos())
				.extracting(CheckResult.Entry::name, CheckResult.Entry::status)
				.containsExactly(tuple("one", CheckResult.Status.ERROR));
		assertThat(result.hasDrift()).isTrue();
	}

	@Test
	void aFailedUserListingIsReportedAgainstEachDeclaredRepository()
			throws Exception {
		stubFor(
				get(urlPathEqualTo("/user/repos")).willReturn(
						aResponse().withStatus(403).withBody("forbidden")
				)
		);

		CheckResult result = GitHubCheck.check(
				config(
						Map.of(),
						Map.of(
								"arlol",
								new Drifty.User(
										List.of(
												Desired.repository("one"),
												Desired.repository("two")
										)
								)
						)
				),
				client,
				orgChecker,
				repoChecker
		);

		assertThat(result.repos())
				.extracting(CheckResult.Entry::name, CheckResult.Entry::status)
				.containsExactlyInAnyOrder(
						tuple("one", CheckResult.Status.ERROR),
						tuple("two", CheckResult.Status.ERROR)
				);
		assertThat(result.hasDrift()).isTrue();
	}

	/**
	 * A personal account has no entry of its own in the report, so a user block
	 * declaring nothing would swallow the failure entirely and the run would
	 * exit 0 on a listing that never succeeded.
	 */
	@Test
	void aFailedUserListingWithNothingDeclaredIsReportedAgainstTheAccount()
			throws Exception {
		stubFor(
				get(urlPathEqualTo("/user/repos"))
						.willReturn(aResponse().withStatus(403))
		);

		CheckResult result = GitHubCheck.check(
				config(Map.of(), Map.of("arlol", new Drifty.User(List.of()))),
				client,
				orgChecker,
				repoChecker
		);

		assertThat(result.repos())
				.extracting(CheckResult.Entry::name, CheckResult.Entry::status)
				.containsExactly(tuple("arlol", CheckResult.Status.ERROR));
		assertThat(result.hasDrift()).isTrue();
	}

	/**
	 * An organization GitHub does not list at all is still missing, not an
	 * error.
	 */
	@Test
	void anUnknownOrganizationStaysMissing() throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/gone/repos"))
						.willReturn(aResponse().withStatus(404))
		);

		CheckResult result = GitHubCheck
				.check(
						config(
								organizations(
										"gone",
										onlySettings().withRepositories(
												List.of(
														Desired.repository(
																"one"
														)
												)
										)
								),
								Map.of()
						),
						client,
						orgChecker,
						repoChecker
				);

		assertThat(result.orgs())
				.extracting(CheckResult.Entry::name, CheckResult.Entry::status)
				.containsExactly(tuple("gone", CheckResult.Status.MISSING));
		assertThat(result.repos())
				.extracting(CheckResult.Entry::name, CheckResult.Entry::status)
				.containsExactly(tuple("one", CheckResult.Status.MISSING));
	}

	private static DriftyConfig config(
			Map<String, Drifty.Organization> organizations,
			Map<String, Drifty.User> users
	) {
		return new DriftyConfig(organizations, users);
	}

	private static Map<String, Drifty.Organization> organizations(
			String login,
			Drifty.Organization organization
	) {
		var map = new LinkedHashMap<String, Drifty.Organization>();
		map.put(login, organization);
		return map;
	}

	/**
	 * Ordered, so the first account named is the first one checked — a failure
	 * on the second would prove nothing about the run continuing.
	 */
	private static Map<String, Drifty.Organization> organizations(
			String firstLogin,
			Drifty.Organization first,
			String secondLogin,
			Drifty.Organization second
	) {
		var map = organizations(firstLogin, first);
		map.put(secondLogin, second);
		return map;
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

	/**
	 * GitHub's defaults, which is what the schema declares, so nothing drifts.
	 */
	private static void stubOrgSettings(String login) {
		stubFor(get(urlPathEqualTo("/orgs/" + login)).willReturn(okJson("""
				{
				  "login": "%s",
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
				""".formatted(login))));
	}

}
