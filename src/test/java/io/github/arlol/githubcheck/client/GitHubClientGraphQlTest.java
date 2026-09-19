package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * The one query that answers what four REST reads answered: a repository's
 * rulesets and their rules, its branch protections, its direct collaborators
 * and whether vulnerability alerts are on.
 * <p>
 * Each of the four is its own aliased {@code repository} selection, so a token
 * that may not read one of them loses that section and keeps the rest — which
 * is the granularity four separate requests had. GitHub nulls the whole
 * {@code repository} object when a field inside it is forbidden.
 */
@WireMockTest
class GitHubClientGraphQlTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	@Test
	void readsAllFourSectionsFromOneRequest() {
		stubFor(
				post(urlPathEqualTo("/graphql")).willReturn(
						okJson(
								"""
										{"data": {
										  "rs": {"rulesets": {"nodes": [{
										      "databaseId": 42, "name": "main-rules",
										      "target": "BRANCH", "enforcement": "ACTIVE",
										      "conditions": {"refName": {"include": ["refs/heads/main"], "exclude": []}},
										      "bypassActors": {"nodes": []},
										      "rules": {"nodes": [{"type": "REQUIRED_LINEAR_HISTORY", "parameters": null}]}
										  }]}},
										  "bp": {"branchProtectionRules": {"nodes": [{
										      "pattern": "main",
										      "matchingRefs": {"nodes": [{"name": "main"}]},
										      "isAdminEnforced": true, "requiresLinearHistory": true,
										      "requiresStatusChecks": false, "requiresApprovingReviews": false,
										      "restrictsPushes": false
										  }]}},
										  "co": {"collaborators": {"edges": [
										      {"permission": "WRITE", "node": {"login": "alice"}}
										  ]}},
										  "va": {"hasVulnerabilityAlertsEnabled": true}
										}}
										"""
						)
				)
		);

		GraphQlRepositoryResponse response = client
				.graphqlRepository("owner", "repo");

		assertThat(response.rulesets()).singleElement().satisfies(ruleset -> {
			assertThat(ruleset.id()).isEqualTo(42);
			assertThat(ruleset.target()).isEqualTo(RulesetTarget.BRANCH);
		});
		assertThat(response.branchProtections()).containsOnlyKeys("main");
		assertThat(
				response.branchProtections()
						.get("main")
						.enforceAdmins()
						.enabled()
		).isTrue();
		assertThat(response.collaborators()).singleElement()
				.satisfies(collaborator -> {
					assertThat(collaborator.login()).isEqualTo("alice");
					assertThat(collaborator.permissions().level())
							.isEqualTo("push");
				});
		assertThat(response.vulnerabilityAlerts()).isTrue();
		verify(1, postRequestedFor(urlPathEqualTo("/graphql")));
	}

	@Test
	void aSectionTheTokenMayNotReadFailsOnItsOwn() {
		stubFor(
				post(urlPathEqualTo("/graphql")).willReturn(
						okJson(
								"""
										{
										  "data": {
										    "rs": {"rulesets": {"nodes": []}},
										    "bp": null,
										    "co": {"collaborators": {"edges": []}},
										    "va": {"hasVulnerabilityAlertsEnabled": false}
										  },
										  "errors": [{
										    "type": "FORBIDDEN",
										    "path": ["bp", "branchProtectionRules"],
										    "message": "Resource not accessible by personal access token"
										  }]
										}
										"""
						)
				)
		);

		GraphQlRepositoryResponse response = client
				.graphqlRepository("owner", "repo");

		assertThat(response.rulesets()).isEmpty();
		assertThat(response.vulnerabilityAlerts()).isFalse();
		assertThatThrownBy(response::branchProtections)
				.isInstanceOf(GitHubApiException.class)
				.hasMessageContaining("not accessible");
	}

}
