package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * The rules are {@link RulesetComparison}'s and covered by
 * {@link RulesetDriftGroupTest}; this checks what only an organization ruleset
 * has: the endpoints and the repository conditions.
 */
@WireMockTest
class OrgRulesetDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static ActualRuleset actual(
			String name,
			RulesetDetailsResponse.Conditions.RepositoryName repositoryName,
			RulesetDetailsResponse.Conditions.RepositoryProperty repositoryProperty,
			List<Rule> rules
	) {
		return ActualTypes.ruleset(
				new RulesetDetailsResponse(
						1L,
						name,
						RulesetTarget.BRANCH,
						RulesetEnforcement.ACTIVE,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						new RulesetDetailsResponse.Conditions(
								new RulesetDetailsResponse.Conditions.RefName(
										List.of(),
										List.of()
								),
								repositoryName,
								null,
								repositoryProperty
						),
						rules
				)
		);
	}

	private OrgRulesetDriftGroup group(
			Map<String, Drifty.OrgRuleset> desired,
			List<ActualRuleset> actual
	) {
		return new OrgRulesetDriftGroup(desired, actual, client, "my-org");
	}

	@Test
	void noDrift_whenRulesAndConditionsMatch() {
		var wanted = Desired.orgRuleset()
				.withNoForcePushes(true)
				.withRepositoryNameInclude(List.of("~ALL"))
				.withRepositoryPropertyInclude(
						List.of(
								Desired.propertyCondition(
										"team",
										List.of("core")
								)
						)
				);
		var got = actual(
				"main",
				new RulesetDetailsResponse.Conditions.RepositoryName(
						List.of("~ALL"),
						List.of(),
						false
				),
				new RulesetDetailsResponse.Conditions.RepositoryProperty(
						List.of(
								new RulesetDetailsResponse.Conditions.RepositoryProperty.PropertyCondition(
										"team",
										List.of("core"),
										"custom"
								)
						),
						List.of()
				),
				List.of(new Rule.NonFastForward())
		);

		var group = group(Map.of("main", wanted), List.of(got));

		assertThat(group.detect()).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.OrgGroupName.ORG_RULESETS);
	}

	@Test
	void repositoryConditionsAndRulesAreCompared() {
		var wanted = Desired.orgRuleset()
				.withNoForcePushes(true)
				.withRepositoryNameInclude(List.of("~ALL"))
				.withRepositoryNameExclude(List.of("sandbox"))
				.withRepositoryNameProtected(true)
				.withRepositoryPropertyExclude(
						List.of(
								Desired.propertyCondition(
										"tier",
										List.of("gold")
								)
						)
				);
		var got = actual("main", null, null, List.of());

		var items = group(Map.of("main", wanted), List.of(got)).detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_rulesets.main.no_force_pushes",
						"org_rulesets.main.repository_name.include",
						"org_rulesets.main.repository_name.exclude",
						"org_rulesets.main.repository_name.protected",
						"org_rulesets.main.repository_property.exclude"
				);
	}

	@Test
	void missingRuleset_isPostedWithTheRepositoryConditions() {
		stubFor(
				post(urlEqualTo("/orgs/my-org/rulesets")).willReturn(
						aResponse().withStatus(201)
								.withBody(
										"{\"id\": 1, \"name\": \"main\", \"rules\": []}"
								)
				)
		);
		var wanted = Desired.orgRuleset()
				.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
				.withRepositoryNameInclude(List.of("~ALL"))
				.withRepositoryNameProtected(true)
				.withRepositoryPropertyInclude(
						List.of(
								Desired.propertyCondition(
										"team",
										List.of("core")
								)
						)
				);

		var fixes = group(Map.of("main", wanted), List.of()).detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlEqualTo("/orgs/my-org/rulesets"))
						.withRequestBody(
								equalToJson(
										"""
												{
												  "name": "main",
												  "target": "branch",
												  "enforcement": "active",
												  "conditions": {
												    "ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []},
												    "repository_name": {"include": ["~ALL"], "exclude": [], "protected": true},
												    "repository_property": {
												      "include": [{"name": "team", "property_values": ["core"], "source": "custom"}],
												      "exclude": []
												    }
												  },
												  "rules": []
												}
												"""
								)
						)
		);
	}

	@Test
	void driftedRuleset_isPutWithoutAPropertyConditionWhenNoneIsNamed() {
		stubFor(
				put(urlEqualTo("/orgs/my-org/rulesets/1")).willReturn(
						aResponse().withStatus(200)
								.withBody(
										"{\"id\": 1, \"name\": \"main\", \"rules\": []}"
								)
				)
		);
		var wanted = Desired.orgRuleset().withDeletion(true);
		var got = actual("main", null, null, List.of());

		var fixes = group(Map.of("main", wanted), List.of(got)).detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				putRequestedFor(
						urlEqualTo("/orgs/my-org/rulesets/1")
				).withRequestBody(equalToJson("""
						{
						  "name": "main",
						  "target": "branch",
						  "enforcement": "active",
						  "conditions": {
						    "ref_name": {"include": [], "exclude": []},
						    "repository_name": {"include": [], "exclude": []}
						  },
						  "rules": [{"type": "deletion"}]
						}
						"""))
		);
	}

	@Test
	void extraRuleset_isDeleted() {
		stubFor(
				delete(urlEqualTo("/orgs/my-org/rulesets/1"))
						.willReturn(aResponse().withStatus(204))
		);

		var fixes = group(
				Map.of(),
				List.of(actual("stray", null, null, List.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(deleteRequestedFor(urlEqualTo("/orgs/my-org/rulesets/1")));
	}

}
