package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;

/**
 * Covers {@link OrgChecker#check}, and in particular which account each
 * repository is looked up under. {@code drifty.pkl} declares an {@code owner}
 * per repository and SPEC.md calls that the targeting mechanism, so a config
 * naming two owners has to reach both.
 */
@WireMockTest
class OrgCheckerCheckTest {

	private OrgChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		checker = new OrgChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				false
		);
	}

	@Test
	void checksEachRepositoryUnderTheOwnerItsConfigDeclares() throws Exception {
		stubOwner("alpha", "one");
		stubOwner("beta", "two");
		stubRepoSubResources();

		CheckResult result = checker.check(
				desired(List.of(entry("alpha", "one"), entry("beta", "two")))
		);

		// Found under its own owner, so neither MISSING (declared but not
		// found there) nor UNKNOWN (found but not declared).
		assertThat(result.repos())
				.extracting(
						CheckResult.RepoCheckResult::name,
						CheckResult.RepoCheckResult::status
				)
				.containsExactlyInAnyOrder(
						tuple("one", CheckResult.Status.DRIFT),
						tuple("two", CheckResult.Status.DRIFT)
				);
		verify(1, getRequestedFor(urlPathEqualTo("/orgs/alpha/repos")));
		verify(1, getRequestedFor(urlPathEqualTo("/orgs/beta/repos")));
	}

	/**
	 * Repository names are only unique within an owner, so two owners may both
	 * have one of the same name and each must be checked against its own config
	 * entry rather than collapsing into one.
	 */
	@Test
	void tellsApartSameNamedRepositoriesUnderDifferentOwners()
			throws Exception {
		stubOwner("alpha", "shared");
		stubOwner("beta", "shared");
		stubRepoSubResources();

		CheckResult result = checker.check(
				desired(
						List.of(
								entry("alpha", "shared"),
								entry("beta", "shared")
						)
				)
		);

		assertThat(result.repos()).hasSize(2);
		assertThat(result.missingCount()).isZero();
		assertThat(result.unknownCount()).isZero();
	}

	/**
	 * In fix mode the report is a FIXED/FAILED line per setting, and every
	 * failure has to say why — SPEC.md's per-setting fix results and its
	 * end-of-run failure summary.
	 */
	@Test
	void fixModeReportsEachSettingAsFixedOrFailedWithAReason(
			WireMockRuntimeInfo wm
	) throws Exception {
		stubOwner("alpha", "one");
		stubRepoSubResources();
		stubFor(
				patch(urlPathMatching("/repos/[^/]+/[^/]+"))
						.willReturn(okJson("{}"))
		);
		stubFor(
				put(urlPathMatching("/repos/[^/]+/[^/]+/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(500))
		);

		var fixer = new OrgChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				true
		);

		CheckResult result = fixer
				.check(desired(List.of(entry("alpha", "one"))));

		var reports = result.repos().getFirst().fixReports();
		assertThat(reports).isNotEmpty();
		assertThat(reports).anyMatch(CheckResult.FixReport::fixed);

		// The one write that failed is reported as such, with the HTTP status.
		assertThat(result.fixFailures()).singleElement().satisfies(failure -> {
			assertThat(failure).startsWith("one: vulnerability_alerts.enabled:")
					.contains("FAILED")
					.contains("500");
		});
		assertThat(result.hasDrift()).as("a failed fix leaves the repo drifted")
				.isTrue();
	}

	@Test
	void printReportShowsFixResultsAndSummarisesFailures(WireMockRuntimeInfo wm)
			throws Exception {
		stubOwner("alpha", "one");
		stubRepoSubResources();
		stubFor(
				patch(urlPathMatching("/repos/[^/]+/[^/]+"))
						.willReturn(okJson("{}"))
		);
		stubFor(
				put(urlPathMatching("/repos/[^/]+/[^/]+/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(500))
		);

		var fixer = new OrgChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				true
		);
		CheckResult result = fixer
				.check(desired(List.of(entry("alpha", "one"))));

		String report = capturePrintReport(fixer, result);

		assertThat(report).contains(": FIXED")
				.contains("vulnerability_alerts.enabled: FAILED")
				.contains("=== Failed fixes (1) ===");
	}

	private static String capturePrintReport(
			OrgChecker checker,
			CheckResult result
	) {
		PrintStream original = System.out;
		var captured = new ByteArrayOutputStream();
		try (var out = new PrintStream(
				captured,
				true,
				StandardCharsets.UTF_8
		)) {
			System.setOut(out);
			checker.printReport(result);
		} finally {
			System.setOut(original);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

	private static RepositoryArgs entry(String owner, String name) {
		return RepositoryArgs.create(owner, name)
				.visibility(RepositoryVisibility.PRIVATE)
				.build();
	}

	private static List<io.github.arlol.githubcheck.pkl.Drifty.Repository> desired(
			List<RepositoryArgs> repos
	) {
		return ToDrifty.repositories(repos);
	}

	/** One owner with one repository, plus that repository's details. */
	private static void stubOwner(String owner, String repo) {
		stubFor(
				get(urlPathEqualTo("/orgs/" + owner + "/repos")).willReturn(
						okJson(
								"""
										[{"name": "%s", "archived": false, "visibility": "private"}]
										"""
										.formatted(repo)
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/repos/" + owner + "/" + repo))
						.willReturn(okJson(details(repo)))
		);
	}

	private static String details(String name) {
		return """
				{
					"id": 1,
					"name": "%s",
					"private": true,
					"fork": false,
					"archived": false,
					"disabled": false,
					"is_template": false,
					"visibility": "private",
					"default_branch": "main",
					"has_issues": true,
					"has_projects": true,
					"has_wiki": true,
					"has_discussions": false,
					"has_pages": false,
					"allow_forking": true,
					"web_commit_signoff_required": false,
					"allow_squash_merge": true,
					"allow_merge_commit": true,
					"allow_rebase_merge": true,
					"allow_auto_merge": false,
					"delete_branch_on_merge": false,
					"allow_update_branch": false
				}
				""".formatted(name);
	}

	/**
	 * The per-repository reads behind {@code fetchState}, stubbed for any owner
	 * and repository since the tests only care about which owner the repository
	 * list came from.
	 */
	private static void stubRepoSubResources() {
		stubFor(
				get(urlPathMatching("/repos/[^/]+/[^/]+/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(404))
		);
		stubFor(
				get(
						urlPathMatching(
								"/repos/[^/]+/[^/]+/automated-security-fixes"
						)
				).willReturn(okJson("{\"enabled\": false}"))
		);
		stubFor(
				get(urlPathMatching("/repos/[^/]+/[^/]+/immutable-releases"))
						.willReturn(okJson("{\"enabled\": false}"))
		);
		stubFor(
				get(
						urlPathMatching(
								"/repos/[^/]+/[^/]+/private-vulnerability-reporting"
						)
				).willReturn(okJson("{\"enabled\": false}"))
		);
		stubFor(
				get(
						urlPathMatching(
								"/repos/[^/]+/[^/]+/code-scanning/default-setup"
						)
				).willReturn(okJson("{\"state\": \"not-configured\"}"))
		);
		stubFor(
				get(urlPathMatching("/repos/[^/]+/[^/]+/actions/secrets"))
						.willReturn(okJson("{\"secrets\": []}"))
		);
		stubFor(
				get(urlPathMatching("/repos/[^/]+/[^/]+/environments"))
						.willReturn(okJson("{\"environments\": []}"))
		);
		stubFor(
				get(
						urlPathMatching(
								"/repos/[^/]+/[^/]+/actions/permissions/workflow"
						)
				).willReturn(
						okJson(
								"{\"default_workflow_permissions\": \"write\","
										+ " \"can_approve_pull_request_reviews\": true}"
						)
				)
		);
		stubFor(
				get(urlPathMatching("/repos/[^/]+/[^/]+/rulesets"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathMatching("/repos/[^/]+/[^/]+/pages"))
						.willReturn(aResponse().withStatus(404))
		);
	}

}
