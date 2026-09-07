package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;

/**
 * Drives {@link ExportRunner#run} against WireMock the way
 * {@code OrganizationCheckerTest} drives {@code OrganizationChecker}: every
 * endpoint {@code ManagedGroups.all(...)} could reach is stubbed, which is what
 * makes a group whose request escaped its guard fail these tests too.
 * <p>
 * The header carries a real timestamp, so only the body after it — everything
 * from {@code amends} on — is asserted with {@code isEqualTo}; the header line
 * itself is checked against a pattern that pins the version and login it names.
 */
@WireMockTest
class ExportRunnerTest {

	private static final String SCHEMA = Path.of("config/drifty.pkl")
			.toAbsolutePath()
			.toString();

	@Test
	void organizationExportWritesSettingsAndItsRepository(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganizationAtItsDefaults("acme");
		stubOrgReposListing("acme", "widget");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "widget");

		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("acme"), out, SCHEMA);

		assertThat(exitCode).isZero();
		String text = Files.readString(out);
		String[] parts = text.split("\n", 4);
		// Surefire never puts a manifest on the test classpath, so
		// getImplementationVersion() reads null here every time — this pins
		// the exact degraded text ("unknown"), not just "some version word",
		// so a regression back to the literal "drifty null" fails this test.
		assertThat(parts[0]).matches(
				Pattern.quote("/// Exported by drifty unknown from acme on ")
						+ "[^ ]+\\."
		);
		assertThat(parts[1]).isEqualTo(
				"/// Only settings that differ from the schema defaults are listed; everything"
		);
		assertThat(parts[2]).isEqualTo("/// absent is at GitHub's default.");
		assertThat(parts[3]).isEqualTo("""
				amends "%s"

				organizations {
				  ["acme"] {
				    repositories {
				      new {
				        name = "widget"
				        hasDiscussions = true
				      }
				    }
				  }
				}
				""".formatted(SCHEMA));
	}

	/**
	 * GitHub's listing order is not drifty's — creating a repository can
	 * reorder the account's listing GitHub returns — so unlike every other
	 * exported collection, {@code repositories} was the one left unsorted.
	 */
	@Test
	void repositoriesAreExportedSortedByNameRegardlessOfListingOrder(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganizationAtItsDefaults("acme");
		stubOrgReposListing("acme", "zebra", "api");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "zebra");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "api");

		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("acme"), out, SCHEMA);

		assertThat(exitCode).isZero();
		String text = Files.readString(out);
		String body = text.substring(text.indexOf("amends "));
		assertThat(body).isEqualTo("""
				amends "%s"

				organizations {
				  ["acme"] {
				    repositories {
				      new {
				        name = "api"
				        hasDiscussions = true
				      }
				      new {
				        name = "zebra"
				        hasDiscussions = true
				      }
				    }
				  }
				}
				""".formatted(SCHEMA));
	}

	@Test
	void aRepositoryThatFailsToFetchBecomesANoteInsteadOfAbortingTheExport(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganizationAtItsDefaults("acme");
		stubOrgReposListing("acme", "widget", "broken");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "widget");
		stubFor(
				get(urlPathEqualTo("/repos/acme/broken"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);

		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		Path out = dir.resolve("export.pkl");

		// A per-repository failure is a gap in the file, not a failed export:
		// widget's own entry is unaffected and acme still counts as exported.
		int exitCode = ExportRunner.run(client, List.of("acme"), out, SCHEMA);

		assertThat(exitCode).isZero();
		String text = Files.readString(out);
		String body = text.substring(text.indexOf("amends "));
		assertThat(body).isEqualTo("""
				amends "%s"

				organizations {
				  ["acme"] {
				    repositories {
				      // broken: HTTP 403 fetching repo acme/broken
				      new {
				        name = "widget"
				        hasDiscussions = true
				      }
				    }
				  }
				}
				""".formatted(SCHEMA));
	}

	/**
	 * Regression test for a bug review caught: one shared
	 * {@code RepositoryChecker}/{@code FetchFailures.collecting()} across the
	 * whole account loop would carry {@code leaky}'s failure into whichever
	 * repository rendered next. Two repositories both fetch cleanly except
	 * {@code leaky}'s own {@code actions/secrets} read, which 403s — the note
	 * must land only on {@code leaky}, in {@code actionsSecrets}'s position,
	 * and {@code widget} must carry none at all.
	 */
	@Test
	void aGroupFailureOnOneRepositoryDoesNotLeakIntoAnother(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganizationAtItsDefaults("acme");
		stubOrgReposListing("acme", "widget", "leaky");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "widget");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "leaky");
		// Registered after the helper's own success stub for the same path;
		// WireMock resolves a request to the most-recently-registered match.
		stubFor(
				get(urlPathEqualTo("/repos/acme/leaky/actions/secrets"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);

		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("acme"), out, SCHEMA);

		assertThat(exitCode).isZero();
		String text = Files.readString(out);
		String body = text.substring(text.indexOf("amends "));
		assertThat(body).isEqualTo("""
				amends "%s"

				organizations {
				  ["acme"] {
				    repositories {
				      new {
				        name = "leaky"
				        hasDiscussions = true
				        // action_secrets: HTTP 403 for action secrets on leaky
				      }
				      new {
				        name = "widget"
				        hasDiscussions = true
				      }
				    }
				  }
				}
				""".formatted(SCHEMA));
	}

	/**
	 * The file's own note is only visible to someone who opens it; a scripted
	 * {@code drifty --export acme && drifty --fix} needs the same information
	 * on stderr. Exit code stays 0 — an unreadable group is a gap in the file,
	 * not a failed export, and this test pins that alongside the new stderr
	 * line so a future change cannot fix one while breaking the other.
	 */
	@Test
	void anUnreadableGroupIsSummarizedOnStderr(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganizationAtItsDefaults("acme");
		stubOrgReposListing("acme", "widget", "leaky");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "widget");
		stubRepositoryAtItsDefaultsExceptHasDiscussions("acme", "leaky");
		stubFor(
				get(urlPathEqualTo("/repos/acme/leaky/actions/secrets"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);

		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		Path out = dir.resolve("export.pkl");

		PrintStream originalErr = System.err;
		var capturedErr = new ByteArrayOutputStream();
		int exitCode;
		try (var err = new PrintStream(
				capturedErr,
				true,
				StandardCharsets.UTF_8
		)) {
			System.setErr(err);
			exitCode = ExportRunner.run(client, List.of("acme"), out, SCHEMA);
		} finally {
			System.setErr(originalErr);
		}

		assertThat(exitCode).isZero();
		assertThat(capturedErr.toString(StandardCharsets.UTF_8)).isEqualTo(
				"1 group(s) could not be read and are noted in the file instead: action_secrets\n"
		);
	}

	@Test
	void aPersonalLoginThatIsNotTheTokenOwnerFailsWithAClearMessage(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/someone-else"))
						.willReturn(aResponse().withStatus(404))
		);
		stubFor(get(urlPathEqualTo("/user")).willReturn(okJson("""
				{
				  "login": "token-owner",
				  "id": 1,
				  "node_id": "n1",
				  "avatar_url": "https://example.com/a.png",
				  "url": "https://api.github.com/users/token-owner",
				  "html_url": "https://github.com/token-owner",
				  "type": "User",
				  "site_admin": false
				}
				""")));

		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		Path out = dir.resolve("export.pkl");

		PrintStream originalErr = System.err;
		var capturedErr = new ByteArrayOutputStream();
		int exitCode;
		try (var err = new PrintStream(
				capturedErr,
				true,
				StandardCharsets.UTF_8
		)) {
			System.setErr(err);
			exitCode = ExportRunner
					.run(client, List.of("someone-else"), out, SCHEMA);
		} finally {
			System.setErr(originalErr);
		}

		assertThat(exitCode).isEqualTo(1);
		assertThat(capturedErr.toString(StandardCharsets.UTF_8)).isEqualTo(
				"ERROR: someone-else is not an organization, and a personal account can only be exported by its own token\n"
		);
		// The file is still written, with no entry for the failed login.
		String text = Files.readString(out);
		String body = text.substring(text.indexOf("amends "));
		assertThat(body).isEqualTo("""
				amends "%s"

				organizations {
				}
				""".formatted(SCHEMA));
	}

	// ─── Stubs
	// ──────────────────────────────────────────────────────────────

	private static void stubOrganizationAtItsDefaults(String login) {
		stubFor(get(urlPathEqualTo("/orgs/" + login)).willReturn(okJson("""
				{
				  "login": "%s",
				  "description": null,
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
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/actions/permissions"))
						.willReturn(okJson("""
								{
								  "enabled_repositories": "all",
								  "allowed_actions": "all"
								}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/" + login
										+ "/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "write",
						  "can_approve_pull_request_reviews": true
						}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/actions/variables"))
						.willReturn(okJson("""
								{"variables": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/hooks"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/properties/schema"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/rulesets"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/" + login
										+ "/code-security/configurations"
						)
				).willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/teams"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/members"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/actions/runner-groups"))
						.willReturn(okJson("""
								{"runner_groups": []}
								"""))
		);
	}

	private static void stubOrgReposListing(String login, String... names) {
		StringBuilder body = new StringBuilder("[");
		for (int i = 0; i < names.length; i++) {
			if (i > 0) {
				body.append(",");
			}
			body.append(
					"""
							{"id": %d, "name": "%s", "archived": false, "visibility": "public"}
							"""
							.formatted(i + 1, names[i])
			);
		}
		body.append("]");
		stubFor(
				get(urlPathEqualTo("/orgs/" + login + "/repos"))
						.willReturn(okJson(body.toString()))
		);
	}

	/**
	 * Every field matches {@code config/drifty.pkl}'s {@code Repository}
	 * defaults except {@code has_discussions}, which is the one field this test
	 * expects to see exported.
	 */
	private static void stubRepositoryAtItsDefaultsExceptHasDiscussions(
			String owner,
			String repo
	) {
		stubFor(
				get(urlPathEqualTo("/repos/" + owner + "/" + repo)).willReturn(
						okJson(
								"""
										{
										  "id": 1,
										  "name": "%s",
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
										  "has_discussions": true,
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
										  "merge_commit_message": "PR_TITLE",
										  "security_and_analysis": {
										    "secret_scanning": {"status": "enabled"},
										    "secret_scanning_push_protection": {"status": "enabled"},
										    "secret_scanning_non_provider_patterns": {"status": "disabled"},
										    "secret_scanning_validity_checks": {"status": "disabled"}
										  }
										}
										"""
										.formatted(repo)
						)
				)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/vulnerability-alerts"
						)
				).willReturn(aResponse().withStatus(204))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/automated-security-fixes"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/immutable-releases"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/private-vulnerability-reporting"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/code-scanning/default-setup"
						)
				).willReturn(okJson("""
						{"state": "not-configured"}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo + "/branches"
						)
				).willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/actions/secrets"
						)
				).willReturn(okJson("""
						{"secrets": []}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/actions/variables"
						)
				).willReturn(okJson("""
						{"variables": []}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo + "/environments"
						)
				).willReturn(okJson("""
						{"environments": []}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/" + owner + "/" + repo + "/hooks"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/collaborators"
						)
				).willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/properties/values"
						)
				).willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo
										+ "/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "write",
						  "can_approve_pull_request_reviews": true
						}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/" + owner + "/" + repo + "/rulesets"
						)
				).willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/" + owner + "/" + repo + "/pages"))
						.willReturn(aResponse().withStatus(404))
		);
	}

}
