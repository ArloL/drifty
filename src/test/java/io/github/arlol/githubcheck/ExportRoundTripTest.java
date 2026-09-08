package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Exports a state and loads the exported file straight back through
 * {@link PklConfigLoader} and {@link GitHubCheck#check}, against the same
 * WireMock state the export was taken from: the property this protects is that
 * {@code --export}'s output is a config an unmodified {@code drifty} accepts as
 * already-satisfied, not merely a file that parses.
 * <p>
 * A file that reports drift against the state it came from would tell a
 * {@code --fix} run to change something back to a value the organization never
 * chose — every exporter's own unit tests check what one field renders as, but
 * only loading the assembled file back through the real checker can catch a
 * field the assembler dropped, mis-keyed, or paired with the wrong schema
 * default. The fixture below is deliberately not at GitHub's defaults: an
 * organization at its defaults exports an empty file and would pass this test
 * whether or not the exporter worked at all.
 */
@WireMockTest
class ExportRoundTripTest {

	private static final String SCHEMA = Path.of("config/drifty.pkl")
			.toAbsolutePath()
			.toString();

	@Test
	void anExportedFileReportsNoDriftAgainstTheStateItCameFrom(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganization();
		stubRepository();

		GitHubClient client = new GitHubClient(
				wm.getHttpBaseUrl(),
				"test-token"
		);
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("my-org"), out, SCHEMA);
		assertThat(exitCode).isZero();

		DriftyConfig config = PklConfigLoader.load(out);
		var orgChecker = new OrganizationChecker(
				client,
				false,
				Map.of(),
				new DriftyState()
		);
		var repoChecker = new RepositoryChecker(client, false);

		CheckResult result = GitHubCheck
				.check(config, client, orgChecker, repoChecker);

		// hasDrift() alone would only say "something is wrong" — naming the
		// diffs is what turns a failure here into "fix this exporter field"
		// instead of an hour spent re-deriving which one from scratch.
		assertThat(problems(result)).isEmpty();
		assertThat(result.hasDrift()).isFalse();
	}

	/**
	 * Issue #136: a group the export could not read has to come back as an
	 * unmanaged group, not only as a {@code //} note.
	 * <p>
	 * The note says which request failed and why, but no later run can act on a
	 * comment — so an export whose token could not read one group produced a
	 * file whose very first {@code drifty} run died on exactly the request the
	 * export had already given up on. Both 403s below stay stubbed for the
	 * check that follows: the run passes because {@code managed} keeps the
	 * requests from being sent at all, not because the endpoints recovered.
	 * <p>
	 * One organization group and one repository group, since {@code managed}
	 * and {@code OrgManaged} are separate fields on separate types and an
	 * export that filled in only one of them would still leave half its file
	 * uncheckable.
	 */
	@Test
	void aGroupTheExportCouldNotReadIsLeftUnmanagedSoTheFileStillChecks(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganization();
		stubRepository();
		// Registered last, so WireMock resolves these two paths to the 403
		// rather than to the success stubs above.
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/secrets"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);

		GitHubClient client = new GitHubClient(
				wm.getHttpBaseUrl(),
				"test-token"
		);
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("my-org"), out, SCHEMA);
		assertThat(exitCode).isZero();

		DriftyConfig config = PklConfigLoader.load(out);
		assertThat(config.organizations().get("my-org").managed.groups)
				.containsExactly(Drifty.OrgGroupName.ORG_TEAMS);
		assertThat(
				config.organizations().get("my-org").repositories
						.getFirst().managed.groups
		).containsExactly(Drifty.GroupName.ACTION_SECRETS);

		CheckResult result = GitHubCheck.check(
				config,
				client,
				new OrganizationChecker(
						client,
						false,
						Map.of(),
						new DriftyState()
				),
				new RepositoryChecker(client, false)
		);

		assertThat(problems(result)).isEmpty();
		assertThat(result.hasDrift()).isFalse();
	}

	/**
	 * Every diff, plus an ERROR's message and a MISSING entry's name, since
	 * neither of those carry a diff of its own but both mean the round trip
	 * failed just as much as a DRIFT would.
	 */
	private static List<String> problems(CheckResult result) {
		List<String> problems = new ArrayList<>();
		for (CheckResult.Entry entry : result.orgs()) {
			problems.addAll(problemsFor(entry));
		}
		for (CheckResult.Entry entry : result.repos()) {
			problems.addAll(problemsFor(entry));
		}
		return problems;
	}

	private static List<String> problemsFor(CheckResult.Entry entry) {
		var problems = new ArrayList<String>();
		problems.addAll(entry.diffs());
		if (entry.status() == CheckResult.Status.ERROR) {
			problems.add(entry.name() + ": ERROR: " + entry.error());
		}
		if (entry.status() == CheckResult.Status.MISSING) {
			problems.add(entry.name() + ": MISSING");
		}
		// UNKNOWN is a repository GitHub has that the config never names —
		// exactly what happens to one the export could not fetch details
		// for: it becomes a note in the listing rather than an entry, so the
		// round trip's own config omits it too. That is real information
		// this test exists to catch, not something to wave through the way
		// skipping this status did.
		if (entry.status() == CheckResult.Status.UNKNOWN) {
			problems.add(entry.name() + ": UNKNOWN");
		}
		return problems;
	}

	// ─── Stubs ────────────────────────────────────────────────────────────

	/**
	 * An organization with a non-default {@code description}, a check-only
	 * setting drifty can report but never write
	 * ({@code twoFactorRequirementEnabled}), one team, one ruleset with a
	 * {@code pullRequest} rule, and one webhook. Every other field matches
	 * {@code config/drifty.pkl}'s defaults, so only the fields named above are
	 * expected to appear in the exported file.
	 */
	private static void stubOrganization() {
		stubFor(get(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("""
				{
				  "login": "my-org",
				  "description": "Widgets, Inc.",
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
				  "members_can_view_dependency_insights": true,
				  "two_factor_requirement_enabled": true
				}
				""")));
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
								  "default_workflow_permissions": "write",
								  "can_approve_pull_request_reviews": true
								}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/variables"))
						.willReturn(okJson("""
								{"variables": []}
								"""))
		);
		stubFor(get(urlPathEqualTo("/orgs/my-org/hooks")).willReturn(okJson("""
				[
				  {
				    "id": 7,
				    "name": "web",
				    "active": true,
				    "events": ["pull_request"],
				    "config": {
				      "url": "https://example.com/hook",
				      "content_type": "json",
				      "insecure_ssl": "0"
				    },
				    "updated_at": "2024-01-01T00:00:00Z"
				  }
				]
				""")));
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/properties/schema"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "protect-main", "source_type": "Organization", "enforcement": "active"}
										]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets/1")).willReturn(
						okJson(
								"""
										{
										  "id": 1,
										  "name": "protect-main",
										  "target": "branch",
										  "enforcement": "active",
										  "source_type": "Organization",
										  "conditions": {
										    "ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}
										  },
										  "rules": [
										    {"type": "pull_request", "parameters": {"required_approving_review_count": 1}}
										  ]
										}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/code-security/configurations"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "platform", "slug": "platform", "description": null, "privacy": "closed",
										   "notification_setting": "notifications_enabled", "parent": null}
										]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams/platform/members"))
						.withQueryParam("role", equalTo("member"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams/platform/members"))
						.withQueryParam("role", equalTo("maintainer"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.withQueryParam("role", equalTo("admin"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.withQueryParam("role", equalTo("member"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/runner-groups"))
						.willReturn(okJson("""
								{"runner_groups": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/repos")).willReturn(
						okJson(
								"""
										[{"id": 1, "name": "widget", "archived": false, "visibility": "public"}]
										"""
						)
				)
		);
	}

	/**
	 * One repository with a non-default merge setting
	 * ({@code deleteBranchOnMerge}) and one environment with a non-default wait
	 * timer. Every other field matches {@code config/drifty.pkl}'s repository
	 * defaults.
	 */
	private static void stubRepository() {
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget")).willReturn(
						okJson(
								"""
										{
										  "id": 1,
										  "name": "widget",
										  "private": false,
										  "fork": false,
										  "archived": false,
										  "disabled": false,
										  "is_template": false,
										  "visibility": "public",
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
										  "delete_branch_on_merge": true,
										  "allow_update_branch": false,
										  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
										  "squash_merge_commit_message": "COMMIT_MESSAGES",
										  "merge_commit_title": "MERGE_MESSAGE",
										  "merge_commit_message": "PR_TITLE",
										  "security_and_analysis": {
										    "secret_scanning": {"status": "enabled"},
										    "secret_scanning_push_protection": {"status": "enabled"},
										    "secret_scanning_non_provider_patterns": {"status": "disabled"},
										    "secret_scanning_validity_checks": {"status": "disabled"}
										  }
										}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(204))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/automated-security-fixes"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/immutable-releases"))
						.willReturn(okJson("""
								{"enabled": false}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/private-vulnerability-reporting"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/code-scanning/default-setup"
						)
				).willReturn(okJson("""
						{"state": "not-configured"}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/branches"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/variables"))
						.willReturn(okJson("""
								{"variables": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/environments"))
						.willReturn(okJson("""
								{
								  "environments": [
								    {
								      "name": "prod",
								      "protection_rules": [
								        {"type": "wait_timer", "wait_timer": 10}
								      ]
								    }
								  ]
								}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/environments/prod/secrets"
						)
				).willReturn(okJson("""
						{"secrets": []}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/environments/prod/variables"
						)
				).willReturn(okJson("""
						{"variables": []}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/hooks"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/collaborators"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/teams"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/properties/values"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "write",
						  "can_approve_pull_request_reviews": true
						}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/rulesets"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/pages"))
						.willReturn(aResponse().withStatus(404))
		);
	}

}
