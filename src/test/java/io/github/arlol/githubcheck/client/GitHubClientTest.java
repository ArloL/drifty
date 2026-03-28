package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.RepositoryFull;
import io.github.arlol.githubcheck.client.RepositoryVisibility;

@WireMockTest
class GitHubClientTest {

	private GitHubClient client;
	private String baseUrl;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		baseUrl = wm.getHttpBaseUrl();
		client = new GitHubClient(baseUrl, "test-token");
	}

	// ─── listOrgRepos
	// ───────────────────────────────────────────────────────────

	@Test
	void listOrgRepos_singlePage() throws Exception {
		stubFor(get(urlPathEqualTo("/orgs/ArloL/repos")).willReturn(okJson("""
				[
				  {"name": "repo-a", "archived": false, "visibility": "public"},
				  {"name": "repo-b", "archived": true,  "visibility": "private"}
				]
				""")));

		List<RepositoryMinimal> repos = client.listOrgRepos("ArloL");

		assertThat(repos).hasSize(2);
		assertThat(repos.get(0).name()).isEqualTo("repo-a");
		assertThat(repos.get(0).archived()).isFalse();
		assertThat(repos.get(0).visibility())
				.isEqualTo(RepositoryVisibility.PUBLIC);
		assertThat(repos.get(1).name()).isEqualTo("repo-b");
		assertThat(repos.get(1).archived()).isTrue();
		assertThat(repos.get(1).visibility())
				.isEqualTo(RepositoryVisibility.PRIVATE);
	}

	@Test
	void listOrgRepos_multiPage() throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/ArloL/repos"))
						.withQueryParam("page", absent())
						.willReturn(
								okJson(
										"""
												[{"name": "repo-page1", "archived": false, "visibility": "public"}]
												"""
								).withHeader(
										"Link",
										"<" + baseUrl
												+ "/orgs/ArloL/repos?page=2>; rel=\"next\", <"
												+ baseUrl
												+ "/orgs/ArloL/repos?page=2>; rel=\"last\""
								)
						)
		);

		stubFor(
				get(urlPathEqualTo("/orgs/ArloL/repos"))
						.withQueryParam("page", equalTo("2"))
						.willReturn(
								okJson(
										"""
												[{"name": "repo-page2", "archived": false, "visibility": "public"}]
												"""
								)
						)
		);

		List<RepositoryMinimal> repos = client.listOrgRepos("ArloL");

		assertThat(repos).hasSize(2);
		assertThat(repos.get(0).name()).isEqualTo("repo-page1");
		assertThat(repos.get(1).name()).isEqualTo("repo-page2");
	}

	@Test
	void listOrgRepos_emptyOrg() throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/ArloL/repos"))
						.willReturn(okJson("[]"))
		);

		List<RepositoryMinimal> repos = client.listOrgRepos("ArloL");
		assertThat(repos).isEmpty();
	}

	@Test
	void listOrgRepos_fallsBackToUserEndpointOn404() throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/ArloL/repos")).willReturn(
						aResponse().withStatus(404)
								.withHeader("Content-Type", "application/json")
								.withBody(
										"""
												{"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}
												"""
								)
				)
		);
		stubFor(get(urlPathEqualTo("/user/repos")).willReturn(okJson("""
				[
				  {"name": "repo-a", "archived": false, "visibility": "public"},
				  {"name": "repo-b", "archived": true,  "visibility": "private"}
				]
				""")));

		List<RepositoryMinimal> repos = client.listOrgRepos("ArloL");

		assertThat(repos).hasSize(2);
		assertThat(repos.get(0).name()).isEqualTo("repo-a");
		assertThat(repos.get(1).name()).isEqualTo("repo-b");
		assertThat(repos.get(1).archived()).isTrue();
	}

	@Test
	void listOrgRepos_errorThrows() {
		stubFor(
				get(urlPathEqualTo("/orgs/ArloL/repos")).willReturn(
						aResponse().withStatus(404)
								.withHeader("Content-Type", "application/json")
								.withBody(
										"""
												{"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}
												"""
								)
				)
		);
		stubFor(
				get(urlPathEqualTo("/user/repos")).willReturn(
						aResponse().withStatus(403)
								.withHeader("Content-Type", "application/json")
								.withBody("""
										{"message":"Forbidden"}
										""")
				)
		);

		assertThatThrownBy(() -> client.listOrgRepos("ArloL"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("HTTP 403");
	}

	// ─── getRepo
	// ────────────────────────────────────────────────────────────────

	/** Full real-shaped JSON for a repo response. Reused across tests. */
	private static final String REPO_BASE_JSON = """
			{
			  "id": 1,
			  "node_id": "R_1",
			  "name": "my-repo",
			  "full_name": "ArloL/my-repo",
			  "private": false,
			  "fork": false,
			  "archived": false,
			  "disabled": false,
			  "is_template": false,
			  "visibility": "public",
			  "default_branch": "main",
			  "topics": [],
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
			  "allow_update_branch": false,
			  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
			  "squash_merge_commit_message": "COMMIT_MESSAGES",
			  "merge_commit_title": "MERGE_MESSAGE",
			  "merge_commit_message": "PR_TITLE"
			}
			""";

	@Test
	void getRepo_parsesAllFields() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo")).willReturn(
						okJson(
								"""
										{
										  "id": 1,
										  "node_id": "R_1",
										  "name": "my-repo",
										  "full_name": "ArloL/my-repo",
										  "owner": {
										    "login": "ArloL",
										    "id": 123,
										    "node_id": "U_123",
										    "avatar_url": "https://avatars.githubusercontent.com/u/123?v=4",
										    "gravatar_id": "",
										    "url": "https://api.github.com/users/ArloL",
										    "html_url": "https://github.com/ArloL",
										    "type": "User",
										    "site_admin": false
										  },
										  "private": false,
										  "html_url": "https://github.com/ArloL/my-repo",
										  "description": "My repo",
										  "fork": false,
										  "url": "https://api.github.com/repos/ArloL/my-repo",
										  "git_url": "git://github.com/ArloL/my-repo.git",
										  "ssh_url": "git@github.com:ArloL/my-repo.git",
										  "clone_url": "https://github.com/ArloL/my-repo.git",
										  "svn_url": "https://svn.github.com/ArloL/my-repo",
										  "mirror_url": null,
										  "hooks_url": "https://api.github.com/repos/ArloL/my-repo/hooks",
										  "homepage": "https://example.com",
										  "language": "Java",
										  "archived": false,
										  "disabled": false,
										  "is_template": false,
										  "visibility": "public",
										  "default_branch": "main",
										  "topics": ["java", "github"],
										  "forks_count": 5,
										  "stargazers_count": 10,
										  "watchers_count": 10,
										  "size": 100,
										  "open_issues_count": 3,
										  "has_issues": true,
										  "has_projects": true,
										  "has_wiki": true,
										  "has_discussions": false,
										  "has_pages": false,
										  "allow_forking": true,
										  "web_commit_signoff_required": false,
										  "allow_squash_merge": false,
										  "allow_merge_commit": false,
										  "allow_rebase_merge": true,
										  "allow_auto_merge": true,
										  "delete_branch_on_merge": true,
										  "allow_update_branch": false,
										  "squash_merge_commit_title": "PR_TITLE",
										  "squash_merge_commit_message": "PR_BODY",
										  "merge_commit_title": "PR_TITLE",
										  "merge_commit_message": "PR_BODY",
										  "pushed_at": "2024-01-15T10:00:00Z",
										  "created_at": "2020-06-01T00:00:00Z",
										  "updated_at": "2024-01-15T10:00:00Z",
										  "permissions": {"admin": true, "maintain": true, "push": true, "triage": true, "pull": true},
										  "subscribers_count": 5,
										  "network_count": 1,
										  "license": {
										    "key": "mit",
										    "name": "MIT License",
										    "url": "https://api.github.com/licenses/mit",
										    "spdx_id": "MIT",
										    "node_id": "MDc6TGljZW5zZTEz"
										  },
										  "forks": 5,
										  "open_issues": 3,
										  "watchers": 10,
										  "security_and_analysis": {
										    "secret_scanning": {"status": "enabled"},
										    "secret_scanning_push_protection": {"status": "enabled"},
										    "advanced_security": {"status": "enabled"},
										    "dependabot_security_updates": {"status": "enabled"},
										    "code_security": {"status": "enabled"}
										  }
										}
										"""
						)
				)
		);

		RepositoryFull details = client.getRepo("ArloL", "my-repo");

		assertThat(details.owner().login()).isEqualTo("ArloL");
		assertThat(details.owner().id()).isEqualTo(123L);
		assertThat(details.owner().type()).isEqualTo("User");
		assertThat(details.htmlUrl())
				.isEqualTo("https://github.com/ArloL/my-repo");
		assertThat(details.description()).isEqualTo("My repo");
		assertThat(details.url())
				.isEqualTo("https://api.github.com/repos/ArloL/my-repo");
		assertThat(details.gitUrl())
				.isEqualTo("git://github.com/ArloL/my-repo.git");
		assertThat(details.sshUrl())
				.isEqualTo("git@github.com:ArloL/my-repo.git");
		assertThat(details.cloneUrl())
				.isEqualTo("https://github.com/ArloL/my-repo.git");
		assertThat(details.mirrorUrl()).isNull();
		assertThat(details.language()).isEqualTo("Java");
		assertThat(details.homepage()).isEqualTo("https://example.com");
		assertThat(details.hasIssues()).isTrue();
		assertThat(details.hasProjects()).isTrue();
		assertThat(details.hasWiki()).isTrue();
		assertThat(details.defaultBranch()).isEqualTo("main");
		assertThat(details.forksCount()).isEqualTo(5);
		assertThat(details.stargazersCount()).isEqualTo(10);
		assertThat(details.openIssuesCount()).isEqualTo(3);
		assertThat(details.allowMergeCommit()).isFalse();
		assertThat(details.allowSquashMerge()).isFalse();
		assertThat(details.allowAutoMerge()).isTrue();
		assertThat(details.deleteBranchOnMerge()).isTrue();
		assertThat(details.allowRebaseMerge()).isTrue();
		assertThat(details.allowUpdateBranch()).isFalse();
		assertThat(details.webCommitSignoffRequired()).isFalse();
		assertThat(details.squashMergeCommitTitle()).isEqualTo("PR_TITLE");
		assertThat(details.mergeCommitTitle()).isEqualTo("PR_TITLE");
		assertThat(details.topics())
				.containsExactlyInAnyOrder("java", "github");
		assertThat(details.pushedAt()).isEqualTo("2024-01-15T10:00:00Z");
		assertThat(details.createdAt()).isEqualTo("2020-06-01T00:00:00Z");
		assertThat(details.permissions().admin()).isTrue();
		assertThat(details.subscribersCount()).isEqualTo(5);
		assertThat(details.networkCount()).isEqualTo(1);
		assertThat(details.license().key()).isEqualTo("mit");
		assertThat(details.license().spdxId()).isEqualTo("MIT");
		assertThat(details.forks()).isEqualTo(5);
		assertThat(details.securityAndAnalysis().secretScanning().status())
				.isEqualTo(SecurityAndAnalysis.StatusObject.Status.ENABLED);
		assertThat(
				details.securityAndAnalysis()
						.secretScanningPushProtection()
						.status()
		).isEqualTo(SecurityAndAnalysis.StatusObject.Status.ENABLED);
		assertThat(details.securityAndAnalysis().advancedSecurity().status())
				.isEqualTo(SecurityAndAnalysis.StatusObject.Status.ENABLED);
		assertThat(details.securityAndAnalysis().codeSecurity().status())
				.isEqualTo(SecurityAndAnalysis.StatusObject.Status.ENABLED);
	}

	@Test
	void getRepo_disabledSecurityAnalysis() throws Exception {
		stubFor(get(urlEqualTo("/repos/ArloL/my-repo")).willReturn(okJson("""
				{
				  "id": 1,
				  "node_id": "R_1",
				  "name": "my-repo",
				  "full_name": "ArloL/my-repo",
				  "private": false,
				  "fork": false,
				  "archived": false,
				  "disabled": false,
				  "is_template": false,
				  "visibility": "public",
				  "default_branch": "main",
				  "description": null,
				  "homepage": null,
				  "topics": [],
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
				  "allow_update_branch": false,
				  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
				  "squash_merge_commit_message": "COMMIT_MESSAGES",
				  "merge_commit_title": "MERGE_MESSAGE",
				  "merge_commit_message": "PR_TITLE",
				  "security_and_analysis": {
				    "secret_scanning": {"status": "disabled"},
				    "secret_scanning_push_protection": {"status": "disabled"}
				  }
				}
				""")));

		RepositoryFull details = client.getRepo("ArloL", "my-repo");

		assertThat(details.securityAndAnalysis().secretScanning().status())
				.isEqualTo(SecurityAndAnalysis.StatusObject.Status.DISABLED);
		assertThat(
				details.securityAndAnalysis()
						.secretScanningPushProtection()
						.status()
		).isEqualTo(SecurityAndAnalysis.StatusObject.Status.DISABLED);
	}

	// ─── getVulnerabilityAlerts
	// ──────────────────────────────────────────────────

	@Test
	void getVulnerabilityAlerts_204_returnsTrue() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(204))
		);

		assertThat(client.getVulnerabilityAlerts("ArloL", "my-repo")).isTrue();
	}

	@Test
	void getVulnerabilityAlerts_404_returnsFalse() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(404))
		);

		assertThat(client.getVulnerabilityAlerts("ArloL", "my-repo")).isFalse();
	}

	@Test
	void getVulnerabilityAlerts_unexpectedStatus_throws() {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(500))
		);

		assertThatThrownBy(
				() -> client.getVulnerabilityAlerts("ArloL", "my-repo")
		).isInstanceOf(RuntimeException.class).hasMessageContaining("500");
	}

	// ─── getAutomatedSecurityFixes
	// ──────────────────────────────────────────────────

	@Test
	void getAutomatedSecurityFixes_enabled_returnsTrue() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/automated-security-fixes"))
						.willReturn(okJson("""
								{"enabled": true}
								"""))
		);

		assertThat(client.getAutomatedSecurityFixes("ArloL", "my-repo"))
				.isTrue();
	}

	@Test
	void getAutomatedSecurityFixes_disabled_returnsFalse() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/automated-security-fixes"))
						.willReturn(okJson("""
								{"enabled": false}
								"""))
		);

		assertThat(client.getAutomatedSecurityFixes("ArloL", "my-repo"))
				.isFalse();
	}

	@Test
	void getAutomatedSecurityFixes_404_returnsFalse() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/automated-security-fixes"))
						.willReturn(aResponse().withStatus(404))
		);

		assertThat(client.getAutomatedSecurityFixes("ArloL", "my-repo"))
				.isFalse();
	}

	@Test
	void getAutomatedSecurityFixes_unexpectedStatus_throws() {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/automated-security-fixes"))
						.willReturn(aResponse().withStatus(500))
		);

		assertThatThrownBy(
				() -> client.getAutomatedSecurityFixes("ArloL", "my-repo")
		).isInstanceOf(RuntimeException.class).hasMessageContaining("500");
	}

	// ─── getBranchProtection
	// ────────────────────────────────────────────────────

	@Test
	void getBranchProtection_full() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/branches/main/protection"))
						.willReturn(
								okJson(
										"""
												{
												  "url": "https://api.github.com/repos/ArloL/my-repo/branches/main/protection",
												  "enforce_admins": {
												    "url": "https://api.github.com/repos/ArloL/my-repo/branches/main/protection/enforce_admins",
												    "enabled": true
												  },
												  "required_linear_history": {"enabled": true},
												  "allow_force_pushes": {"enabled": false},
												  "allow_deletions": {"enabled": false},
												  "block_creations": {"enabled": false},
												  "required_conversation_resolution": {"enabled": true},
												  "required_status_checks": {
												    "url": "https://api.github.com/repos/ArloL/my-repo/branches/main/protection/required_status_checks",
												    "enforcement_level": "non_admins",
												    "strict": false,
												    "checks": [
												      {"context": "check-actions.required-status-check", "app_id": 42},
												      {"context": "CodeQL", "app_id": null}
												    ],
												    "contexts_url": "https://api.github.com/repos/ArloL/my-repo/branches/main/protection/required_status_checks/contexts"
												  },
												  "required_pull_request_reviews": {
												    "url": "https://api.github.com/repos/ArloL/my-repo/branches/main/protection/required_pull_request_reviews",
												    "dismiss_stale_reviews": true,
												    "require_code_owner_reviews": true,
												    "required_approving_review_count": 2,
												    "require_last_push_approval": false
												  },
												  "required_signatures": {
												    "url": "https://api.github.com/repos/ArloL/my-repo/branches/main/protection/required_signatures",
												    "enabled": false
												  },
												  "lock_branch": {"enabled": false},
												  "allow_fork_syncing": {"enabled": true}
												}
												"""
								)
						)
		);

		Optional<BranchProtectionResponse> opt = client
				.getBranchProtection("ArloL", "my-repo", "main");

		assertThat(opt).isPresent();
		BranchProtectionResponse bp = opt.orElseThrow();
		assertThat(bp.url()).isEqualTo(
				"https://api.github.com/repos/ArloL/my-repo/branches/main/protection"
		);
		assertThat(bp.enforceAdmins().url()).isEqualTo(
				"https://api.github.com/repos/ArloL/my-repo/branches/main/protection/enforce_admins"
		);
		assertThat(bp.enforceAdmins().enabled()).isTrue();
		assertThat(bp.requiredLinearHistory().enabled()).isTrue();
		assertThat(bp.allowForcePushes().enabled()).isFalse();
		assertThat(bp.allowDeletions().enabled()).isFalse();
		assertThat(bp.blockCreations().enabled()).isFalse();
		assertThat(bp.requiredConversationResolution().enabled()).isTrue();
		assertThat(bp.requiredStatusChecks().strict()).isFalse();
		assertThat(bp.requiredStatusChecks().enforcementLevel())
				.isEqualTo("non_admins");
		assertThat(bp.requiredStatusChecks().checks()).extracting(
				BranchProtectionResponse.RequiredStatusChecks.StatusCheck::context
		)
				.containsExactlyInAnyOrder(
						"check-actions.required-status-check",
						"CodeQL"
				);
		assertThat(bp.requiredStatusChecks().checks().get(0).appId())
				.isEqualTo(42);
		assertThat(bp.requiredStatusChecks().checks().get(1).appId()).isNull();
		assertThat(bp.requiredPullRequestReviews().dismissStaleReviews())
				.isTrue();
		assertThat(bp.requiredPullRequestReviews().requireCodeOwnerReviews())
				.isTrue();
		assertThat(
				bp.requiredPullRequestReviews().requiredApprovingReviewCount()
		).isEqualTo(2);
		assertThat(bp.requiredSignatures().enabled()).isFalse();
		assertThat(bp.lockBranch().enabled()).isFalse();
		assertThat(bp.allowForkSyncing().enabled()).isTrue();
	}

	@Test
	void getBranchProtection_legacyContextsArray() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/branches/main/protection"))
						.willReturn(
								okJson(
										"""
												{
												  "enforce_admins": {"enabled": false},
												  "required_linear_history": {"enabled": false},
												  "allow_force_pushes": {"enabled": false},
												  "required_status_checks": {
												    "strict": false,
												    "contexts": ["legacy-check-1", "legacy-check-2"]
												  }
												}
												"""
								)
						)
		);

		Optional<BranchProtectionResponse> opt = client
				.getBranchProtection("ArloL", "my-repo", "main");

		assertThat(opt).isPresent();
		assertThat(opt.orElseThrow().requiredStatusChecks().contexts())
				.containsExactlyInAnyOrder("legacy-check-1", "legacy-check-2");
	}

	@Test
	void getBranchProtection_404_returnsEmpty() throws Exception {
		stubFor(
				get(urlEqualTo("/repos/ArloL/my-repo/branches/main/protection"))
						.willReturn(aResponse().withStatus(404))
		);

		assertThat(client.getBranchProtection("ArloL", "my-repo", "main"))
				.isEmpty();
	}

	// ─── getActionSecretNames
	// ───────────────────────────────────────────────────

	@Test
	void getActionSecretNames_parsesNames() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/actions/secrets"))
						.willReturn(
								okJson(
										"""
												{
												  "total_count": 2,
												  "secrets": [
												    {"name": "PAT", "created_at": "2023-01-01T00:00:00Z", "updated_at": "2024-01-01T00:00:00Z"},
												    {"name": "DEPLOY_KEY", "created_at": "2023-02-01T00:00:00Z", "updated_at": "2024-02-01T00:00:00Z"}
												  ]
												}
												"""
								)
						)
		);

		List<String> names = client.getActionSecretNames("ArloL", "my-repo");
		assertThat(names).containsExactlyInAnyOrder("PAT", "DEPLOY_KEY");
	}

	@Test
	void getActionSecretNames_empty() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/actions/secrets"))
						.willReturn(okJson("""
								{"total_count": 0, "secrets": []}
								"""))
		);

		assertThat(client.getActionSecretNames("ArloL", "my-repo")).isEmpty();
	}

	// ─── getEnvironments
	// ────────────────────────────────────────────────────────

	@Test
	void getEnvironments_parsesNames() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/environments"))
						.willReturn(okJson("""
								{
								  "total_count": 2,
								  "environments": [
								    {"name": "production"},
								    {"name": "staging"}
								  ]
								}
								"""))
		);

		var envs = client.getEnvironments("ArloL", "my-repo");
		assertThat(envs).extracting(EnvironmentDetailsResponse::name)
				.containsExactlyInAnyOrder("production", "staging");
	}

	@Test
	void getEnvironments_empty() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/environments"))
						.willReturn(okJson("""
								{"total_count": 0, "environments": []}
								"""))
		);

		assertThat(client.getEnvironments("ArloL", "my-repo")).isEmpty();
	}

	@Test
	void getActionSecretNames_multiPage() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/actions/secrets"))
						.withQueryParam("page", absent())
						.willReturn(
								okJson(
										"""
												{"total_count": 3, "secrets": [{"name": "SECRET_1"}, {"name": "SECRET_2"}]}
												"""
								).withHeader(
										"Link",
										"<" + baseUrl
												+ "/repos/ArloL/my-repo/actions/secrets?page=2>; rel=\"next\""
								)
						)
		);
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/actions/secrets"))
						.withQueryParam("page", equalTo("2"))
						.willReturn(
								okJson(
										"""
												{"total_count": 3, "secrets": [{"name": "SECRET_3"}]}
												"""
								)
						)
		);

		List<String> names = client.getActionSecretNames("ArloL", "my-repo");
		assertThat(names)
				.containsExactlyInAnyOrder("SECRET_1", "SECRET_2", "SECRET_3");
	}

	@Test
	void getEnvironments_multiPage() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/environments"))
						.withQueryParam("page", absent())
						.willReturn(
								okJson(
										"""
												{"total_count": 3, "environments": [{"name": "production"}, {"name": "staging"}]}
												"""
								).withHeader(
										"Link",
										"<" + baseUrl
												+ "/repos/ArloL/my-repo/environments?page=2>; rel=\"next\""
								)
						)
		);
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/environments"))
						.withQueryParam("page", equalTo("2"))
						.willReturn(
								okJson(
										"""
												{"total_count": 3, "environments": [{"name": "dev"}]}
												"""
								)
						)
		);

		var envs = client.getEnvironments("ArloL", "my-repo");
		assertThat(envs).extracting(EnvironmentDetailsResponse::name)
				.containsExactlyInAnyOrder("production", "staging", "dev");
	}

	// ─── getEnvironmentSecretNames
	// ──────────────────────────────────────────────

	@Test
	void getEnvironmentSecretNames_parsesNames() throws Exception {
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/ArloL/my-repo/environments/production/secrets"
						)
				).willReturn(okJson("""
						{
						  "total_count": 1,
						  "secrets": [{"name": "TF_GITHUB_TOKEN"}]
						}
						"""))
		);

		List<String> names = client
				.getEnvironmentSecretNames("ArloL", "my-repo", "production");
		assertThat(names).containsExactly("TF_GITHUB_TOKEN");
	}

	@Test
	void getEnvironmentSecretNames_multiPage() throws Exception {
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/ArloL/my-repo/environments/production/secrets"
						)
				).withQueryParam("page", absent())
						.willReturn(
								okJson(
										"""
												{"total_count": 2, "secrets": [{"name": "SECRET_A"}]}
												"""
								).withHeader(
										"Link",
										"<" + baseUrl
												+ "/repos/ArloL/my-repo/environments/production/secrets?page=2>; rel=\"next\""
								)
						)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/ArloL/my-repo/environments/production/secrets"
						)
				).withQueryParam("page", equalTo("2")).willReturn(okJson("""
						{"total_count": 2, "secrets": [{"name": "SECRET_B"}]}
						"""))
		);

		List<String> names = client
				.getEnvironmentSecretNames("ArloL", "my-repo", "production");
		assertThat(names).containsExactlyInAnyOrder("SECRET_A", "SECRET_B");
	}

	// ─── getWorkflowPermissions
	// ─────────────────────────────────────────────────

	@Test
	void getWorkflowPermissions_parsesFields() throws Exception {
		stubFor(
				get(
						urlEqualTo(
								"/repos/ArloL/my-repo/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "read",
						  "can_approve_pull_request_reviews": true
						}
						"""))
		);

		WorkflowPermissions perms = client
				.getWorkflowPermissions("ArloL", "my-repo");
		assertThat(perms.defaultWorkflowPermissions())
				.isEqualTo(WorkflowPermissions.DefaultWorkflowPermissions.READ);
		assertThat(perms.canApprovePullRequestReviews()).isTrue();
	}

	@Test
	void getWorkflowPermissions_403_throws() {
		stubFor(
				get(
						urlEqualTo(
								"/repos/ArloL/my-repo/actions/permissions/workflow"
						)
				).willReturn(aResponse().withStatus(403))
		);

		assertThatThrownBy(
				() -> client.getWorkflowPermissions("ArloL", "my-repo")
		).isInstanceOf(RuntimeException.class).hasMessageContaining("403");
	}

	// ─── Authorization header
	// ────────────────────────────────────────────────────

	@Test
	void sendsAuthorizationHeader() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/my-repo/environments"))
						.withHeader(
								"Authorization",
								equalTo("Bearer test-token")
						)
						.willReturn(okJson("""
								{"total_count": 0, "environments": []}
								"""))
		);

		// If the header doesn't match, WireMock returns 404 — so successful
		// response confirms header was sent
		client.getEnvironments("ArloL", "my-repo");

		verify(
				getRequestedFor(
						urlPathEqualTo("/repos/ArloL/my-repo/environments")
				).withHeader("Authorization", equalTo("Bearer test-token"))
		);
	}

	@Test
	void pages_exampleResponse() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/github/developer.github.com/pages"))
						.willReturn(
								okJson(
										"""
												{
												   "url": "https://api.github.com/repos/github/developer.github.com/pages",
												   "status": "built",
												   "cname": "developer.github.com",
												   "custom_404": false,
												   "html_url": "https://developer.github.com",
												   "source": {
												     "branch": "master",
												     "path": "/"
												   },
												   "public": true,
												   "pending_domain_unverified_at": "2024-04-30T19:33:31Z",
												   "protected_domain_state": "verified",
												   "https_certificate": {
												     "state": "approved",
												     "description": "Certificate is approved",
												     "domains": [
												       "developer.github.com"
												     ],
												     "expires_at": "2021-05-22"
												   },
												   "https_enforced": true
												 }
												"""
								)
						)
		);

		var pages = client.getPages("github", "developer.github.com")
				.orElseThrow();

		assertThat(pages.url()).isEqualTo(
				"https://api.github.com/repos/github/developer.github.com/pages"
		);
		assertThat(pages.status()).isEqualTo("built");
		assertThat(pages.cname()).isEqualTo("developer.github.com");
		assertThat(pages.custom404()).isFalse();
		assertThat(pages.htmlUrl()).isEqualTo("https://developer.github.com");
		assertThat(pages.buildType()).isNull();
		assertThat(pages.source().branch()).isEqualTo("master");
		assertThat(pages.source().path()).isEqualTo("/");
		assertThat(pages.isPublic()).isTrue();
		assertThat(pages.pendingDomainUnverifiedAt())
				.isEqualTo("2024-04-30T19:33:31Z");
		assertThat(pages.protectedDomainState()).isEqualTo("verified");
		assertThat(pages.httpsCertificate().state()).isEqualTo("approved");
		assertThat(pages.httpsCertificate().domains())
				.containsExactly("developer.github.com");
		assertThat(pages.httpsEnforced()).isTrue();
	}

	@Test
	void pages_notFound() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/dotfiles/pages")).willReturn(
						aResponse().withStatus(404)
								.withHeader("Content-Type", "application/json")
								.withBody(
										"""
												{
												  "message": "Not Found",
												  "documentation_url": "https://docs.github.com/rest/pages/pages#get-a-apiname-pages-site",
												  "status": "404"
												}
												"""
								)
				)
		);

		var pages = client.getPages("ArloL", "dotfiles");

		assertThat(pages).isEmpty();
	}

	@Test
	void pages_realResponse() throws Exception {
		stubFor(
				get(urlPathEqualTo("/repos/ArloL/eclipse-projects/pages"))
						.willReturn(
								okJson(
										"""
												{
												  "url": "https://api.github.com/repos/ArloL/eclipse-projects/pages",
												  "status": "built",
												  "cname": null,
												  "custom_404": false,
												  "html_url": "https://arlol.github.io/eclipse-projects/",
												  "build_type": "workflow",
												  "source": {
												    "branch": "main",
												    "path": "/"
												  },
												  "public": true,
												  "protected_domain_state": null,
												  "pending_domain_unverified_at": null,
												  "https_enforced": true
												}
												"""
								)
						)
		);

		var pages = client.getPages("ArloL", "eclipse-projects").orElseThrow();

		assertThat(pages.buildType())
				.isEqualTo(PagesResponse.BuildType.WORKFLOW);
		assertThat(pages.cname()).isNull();
		assertThat(pages.protectedDomainState()).isNull();
		assertThat(pages.pendingDomainUnverifiedAt()).isNull();
		assertThat(pages.httpsCertificate()).isNull();
		assertThat(pages.httpsEnforced()).isTrue();
	}

	@Test
	void deletePages_204() throws Exception {
		stubFor(
				delete(urlPathEqualTo("/repos/ArloL/my-repo/pages"))
						.willReturn(aResponse().withStatus(204))
		);

		client.deletePages("ArloL", "my-repo");
	}

	@Test
	void deletePages_404() throws Exception {
		stubFor(
				delete(urlPathEqualTo("/repos/ArloL/my-repo/pages"))
						.willReturn(aResponse().withStatus(404))
		);

		client.deletePages("ArloL", "my-repo");
	}

	// ─── updateRepository
	// ──────────────────────────────────────────────────

	@Test
	void updateRepository_singleField() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/my-repo"))
						.willReturn(okJson(REPO_BASE_JSON))
		);

		client.updateRepository(
				"ArloL",
				"my-repo",
				Map.of("description", "New description")
		);

		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/my-repo"))
						.withRequestBody(
								equalToJson(
										"{\"description\":\"New description\"}"
								)
						)
		);
	}

	@Test
	void updateRepository_multipleFields() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/my-repo"))
						.willReturn(okJson(REPO_BASE_JSON))
		);

		client.updateRepository(
				"ArloL",
				"my-repo",
				Map.of(
						"description",
						"New description",
						"has_wiki",
						true,
						"allow_merge_commit",
						false
				)
		);

		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/my-repo"))
						.withRequestBody(equalToJson("""
								{
								  "description": "New description",
								  "has_wiki": true,
								  "allow_merge_commit": false
								}
								"""))
		);
	}

	@Test
	void updateRepository_emptyDescription() throws Exception {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/my-repo"))
						.willReturn(okJson(REPO_BASE_JSON))
		);

		client.updateRepository("ArloL", "my-repo", Map.of("description", ""));

		verify(
				patchRequestedFor(urlEqualTo("/repos/ArloL/my-repo"))
						.withRequestBody(equalToJson("{\"description\":\"\"}"))
		);
	}

	@Test
	void updateRepository_errorThrows() {
		stubFor(
				patch(urlEqualTo("/repos/ArloL/my-repo")).willReturn(
						aResponse().withStatus(422)
								.withHeader("Content-Type", "application/json")
								.withBody("{\"message\":\"Validation Failed\"}")
				)
		);

		assertThatThrownBy(
				() -> client.updateRepository(
						"ArloL",
						"my-repo",
						Map.of("description", "desc")
				)
		).isInstanceOf(RuntimeException.class).hasMessageContaining("HTTP 422");
	}

	// ─── replaceTopics
	// ──────────────────────────────────────────────────

	@Test
	void replaceTopics_success() throws Exception {
		stubFor(
				put(urlEqualTo("/repos/ArloL/my-repo/topics"))
						.willReturn(okJson("{\"names\":[\"java\",\"maven\"]}"))
		);

		client.replaceTopics("ArloL", "my-repo", List.of("java", "maven"));

		verify(
				putRequestedFor(urlEqualTo("/repos/ArloL/my-repo/topics"))
						.withRequestBody(
								equalToJson("{\"names\":[\"java\",\"maven\"]}")
						)
		);
	}

	@Test
	void replaceTopics_errorThrows() {
		stubFor(
				put(urlEqualTo("/repos/ArloL/my-repo/topics")).willReturn(
						aResponse().withStatus(422)
								.withHeader("Content-Type", "application/json")
								.withBody("{\"message\":\"Validation Failed\"}")
				)
		);

		assertThatThrownBy(
				() -> client.replaceTopics("ArloL", "my-repo", List.of("bad"))
		).isInstanceOf(RuntimeException.class).hasMessageContaining("HTTP 422");
	}

}
