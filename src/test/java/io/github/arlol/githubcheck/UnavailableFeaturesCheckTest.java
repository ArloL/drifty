package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * Issue #202: a private repository on a Free account cannot have a wiki, secret
 * scanning or code scanning, and a check has to say so rather than fail on code
 * scanning's 403 or report drift no {@code --fix} can clear.
 */
@WireMockTest
class UnavailableFeaturesCheckTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(
					DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					false
			);

	private static final String CODE_SCANNING_NOT_ENABLED = "Code scanning is not enabled for this repository. Please enable code scanning in the repository settings.";

	private static final List<Drifty.GroupName> GROUPS = List.of(
			Drifty.GroupName.REPO_SETTINGS,
			Drifty.GroupName.SECRET_SCANNING,
			Drifty.GroupName.SECRET_SCANNING_PUSH_PROTECTION,
			Drifty.GroupName.CODE_SCANNING_DEFAULT_SETUP
	);

	private RepositoryChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		checker = new RepositoryChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				false
		);
		stubUser("ArloL", "free");
	}

	/**
	 * The issue's own config — nothing but {@code visibility = "private"} —
	 * against the issue's own repository: no error, and each feature the schema
	 * defaults on is reported with why it cannot be had, with nothing offered
	 * to {@code --fix}.
	 */
	@Test
	void theSchemaDefaultsAreReportedAsUnavailableNotAsDrift()
			throws Exception {
		stubPrivateRepository("ArloL", "stuff", "User", false);

		CheckResult.Entry entry = checkOne("ArloL", "stuff", desired("stuff"));

		assertThat(entry.error()).isNull();
		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.diffs()).containsExactlyInAnyOrder(
				"repo_settings.has_wiki: want=true unavailable: a private repository has a wiki only on a paid plan, and ArloL is on GitHub Free",
				"secret_scanning.enabled: want=true unavailable: GitHub offers secret scanning and advanced security on a private repository only to organizations",
				"secret_scanning_push_protection.enabled: want=true unavailable: GitHub offers secret scanning and advanced security on a private repository only to organizations"
		);
		assertThat(entry.fixPreview()).isEmpty();
	}

	@Test
	void aConfigThatWantsThemOffIsOk() throws Exception {
		stubPrivateRepository("ArloL", "stuff", "User", false);

		CheckResult.Entry entry = checkOne(
				"ArloL",
				"stuff",
				desired("stuff").withHasWiki(false)
						.withSecretScanning(false)
						.withSecretScanningPushProtection(false)
		);

		assertThat(entry.error()).isNull();
		assertThat(entry.diffs()).isEmpty();
		assertThat(entry.status()).isEqualTo(CheckResult.Status.OK);
	}

	@Test
	void codeScanningWantedOnCarriesGitHubsReason() throws Exception {
		stubPrivateRepository("ArloL", "stuff", "User", false);

		CheckResult.Entry entry = checkOne(
				"ArloL",
				"stuff",
				desired("stuff").withHasWiki(false)
						.withSecretScanning(false)
						.withSecretScanningPushProtection(false)
						.withCodeScanningDefaultSetup(true)
		);

		assertThat(entry.diffs()).containsExactly(
				"code_scanning_default_setup.enabled: want=true unavailable: "
						+ CODE_SCANNING_NOT_ENABLED
		);
		assertThat(entry.fixPreview()).isEmpty();
	}

	/** A 403 that is the token's, not the repository's, still errors. */
	@Test
	void aScope403OnCodeScanningIsStillAnError() throws Exception {
		stubPrivateRepository("ArloL", "stuff", "User", false);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/ArloL/stuff/code-scanning/default-setup"
						)
				).willReturn(
						aResponse().withStatus(403)
								.withBody(
										"""
												{"message": "Resource not accessible by personal access token"}
												"""
								)
				)
		);

		CheckResult.Entry entry = checkOne("ArloL", "stuff", desired("stuff"));

		assertThat(entry.error()).contains("403");
	}

	/**
	 * Every repository of an account shares one read of its plan, and a
	 * repository that has the wiki it wants — or is public — sends none.
	 */
	@Test
	void thePlanIsReadOncePerAccountAndOnlyWhereItMatters() throws Exception {
		stubPrivateRepository("ArloL", "one", "User", false);
		stubPrivateRepository("ArloL", "two", "User", false);
		stubPrivateRepository("ArloL", "three", "User", true);

		checker.check(
				"ArloL",
				summaries("one", "two", "three"),
				List.of(desired("one"), desired("two"), desired("three"))
		);

		verify(1, getRequestedFor(urlPathEqualTo("/user")));
	}

	@Test
	void aPrivateRepositoryWithItsWikiOnSendsNoPlanRead() throws Exception {
		stubPrivateRepository("ArloL", "stuff", "User", true);

		checkOne("ArloL", "stuff", desired("stuff"));

		verify(0, getRequestedFor(urlPathEqualTo("/user")));
	}

	/**
	 * GitHub shows a user's plan to that user alone. For any other account the
	 * wiki is compared the way it always was: drift, and something to fix.
	 */
	@Test
	void anotherUsersPlanIsUnknownAndTheWikiIsOrdinaryDrift() throws Exception {
		stubPrivateRepository("someone-else", "stuff", "User", false);

		CheckResult.Entry entry = checkOne(
				"someone-else",
				"stuff",
				desired("stuff").withSecretScanning(false)
						.withSecretScanningPushProtection(false)
		);

		assertThat(entry.diffs())
				.containsExactly("repo_settings.has_wiki: want=true got=false");
		assertThat(entry.fixPreview()).containsExactly("repo_settings");
	}

	/** An organization's plan comes from GET /orgs/{org}, for its owners. */
	@Test
	void aFreeOrganizationsPrivateRepositoryHasNoWiki() throws Exception {
		stubPrivateRepository("acme", "stuff", "Organization", false);
		stubFor(get(urlPathEqualTo("/orgs/acme")).willReturn(okJson("""
				{"login": "acme", "plan": {"name": "free"}}
				""")));

		CheckResult.Entry entry = checkOne(
				"acme",
				"stuff",
				desired("stuff").withSecretScanning(false)
						.withSecretScanningPushProtection(false)
		);

		assertThat(entry.diffs()).containsExactly(
				"repo_settings.has_wiki: want=true unavailable: a private repository has a wiki only on a paid plan, and acme is on GitHub Free"
		);
	}

	@Test
	void anOrganizationOnAPaidPlanCanHaveOne() throws Exception {
		stubPrivateRepository("acme", "stuff", "Organization", false);
		stubFor(get(urlPathEqualTo("/orgs/acme")).willReturn(okJson("""
				{"login": "acme", "plan": {"name": "team"}}
				""")));

		CheckResult.Entry entry = checkOne(
				"acme",
				"stuff",
				desired("stuff").withSecretScanning(false)
						.withSecretScanningPushProtection(false)
		);

		assertThat(entry.diffs())
				.containsExactly("repo_settings.has_wiki: want=true got=false");
	}

	/** A plan read that fails is no plan, not an error for the repository. */
	@Test
	void aFailedPlanReadLeavesTheWikiOrdinaryDrift() throws Exception {
		stubPrivateRepository("acme", "stuff", "Organization", false);
		stubFor(
				get(urlPathEqualTo("/orgs/acme"))
						.willReturn(aResponse().withStatus(500))
		);

		CheckResult.Entry entry = checkOne(
				"acme",
				"stuff",
				desired("stuff").withSecretScanning(false)
						.withSecretScanningPushProtection(false)
		);

		assertThat(entry.error()).isNull();
		assertThat(entry.diffs())
				.containsExactly("repo_settings.has_wiki: want=true got=false");
	}

	// ─── Fixtures
	// ──────────────────────────────────────────────────────────

	private CheckResult.Entry checkOne(
			String owner,
			String name,
			Drifty.Repository desired
	) throws Exception {
		return checker.check(owner, summaries(name), List.of(desired))
				.getFirst();
	}

	private static Drifty.Repository desired(String name) {
		return Desired.repository(name)
				.withVisibility(Drifty.Visibility.PRIVATE)
				.withManaged(
						new Drifty.Managed(Drifty.ManageMode.ONLY, GROUPS)
				);
	}

	private static List<RepositorySummaryResponse> summaries(String... names) {
		return Arrays.stream(names).map(name -> {
			try {
				return MAPPER.readValue(
						"""
								{"name": "%s", "archived": false, "visibility": "private"}
								"""
								.formatted(name),
						RepositorySummaryResponse.class
				);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}).toList();
	}

	private static void stubUser(String login, String plan) {
		stubFor(
				get(urlPathEqualTo("/user")).willReturn(
						okJson(
								"""
										{"login": "%s", "id": 1, "plan": {"name": "%s", "space": 976562499, "collaborators": 0, "private_repos": 10000}}
										"""
										.formatted(login, plan)
						)
				)
		);
	}

	/**
	 * A private repository as a Free account's token sees it: no
	 * {@code security_and_analysis} section, and code scanning's 403.
	 */
	private static void stubPrivateRepository(
			String owner,
			String name,
			String ownerType,
			boolean hasWiki
	) {
		stubFor(
				get(
						urlPathEqualTo("/repos/" + owner + "/" + name)
				).willReturn(okJson("""
						{
						  "id": 1,
						  "name": "%s",
						  "owner": {"login": "%s", "type": "%s"},
						  "private": true,
						  "fork": false,
						  "archived": false,
						  "disabled": false,
						  "is_template": false,
						  "visibility": "private",
						  "default_branch": "main",
						  "has_issues": true,
						  "has_projects": true,
						  "has_wiki": %s,
						  "has_discussions": false,
						  "has_pages": false,
						  "allow_forking": true,
						  "web_commit_signoff_required": false,
						  "allow_squash_merge": true,
						  "allow_merge_commit": true,
						  "allow_rebase_merge": true,
						  "allow_auto_merge": false,
						  "delete_branch_on_merge": false,
						  "allow_update_branch": false,
						  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
						  "squash_merge_commit_message": "COMMIT_MESSAGES",
						  "merge_commit_title": "MERGE_MESSAGE",
						  "merge_commit_message": "PR_TITLE"
						}
						""".formatted(name, owner, ownerType, hasWiki)))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + name
										+ "/code-scanning/default-setup"
						)
				).willReturn(
						aResponse().withStatus(403)
								.withHeader("Content-Type", "application/json")
								.withBody(
										"""
												{"message": "%s", "status": "403"}
												"""
												.formatted(
														CODE_SCANNING_NOT_ENABLED
												)
								)
				)
		);
	}

}
