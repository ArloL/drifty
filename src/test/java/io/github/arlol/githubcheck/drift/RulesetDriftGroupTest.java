package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;

import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.BypassActorArgs;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;
import io.github.arlol.githubcheck.testsupport.RulesetArgs;
import io.github.arlol.githubcheck.testsupport.StatusCheckArgs;

@WireMockTest
class RulesetDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static RulesetDetailsResponse matchingResponse(String name) {
		return new RulesetDetailsResponse(
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
						null,
						null,
						null
				),
				List.of()
		);
	}

	@Test
	void extraRulesetFix_deletesIt() {
		stubFor(
				delete(urlEqualTo("/repos/owner/repo/rulesets/1"))
						.willReturn(aResponse().withStatus(204))
		);
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("ci")),
				client,
				new RepoRef("owner", "repo")
		);

		var result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).isEmpty();
		verify(deleteRequestedFor(urlEqualTo("/repos/owner/repo/rulesets/1")));
	}

	/**
	 * The create payload has to carry the bypass actors, translated from the
	 * Pkl enum names into the API's spellings.
	 */
	@Test
	void missingRulesetFix_createsItWithBypassActors() {
		stubFor(
				post(urlEqualTo("/repos/owner/repo/rulesets")).willReturn(
						aResponse().withStatus(201)
								.withHeader("Content-Type", "application/json")
								.withBody("""
										{"id": 1, "name": "ci", "rules": []}
										""")
				)
		);
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.bypassActors(
										new BypassActorArgs(
												5L,
												RulesetDetailsResponse.BypassActor.ActorType.TEAM,
												RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
										)
								)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(),
				client,
				new RepoRef("owner", "repo")
		);

		var result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlEqualTo("/repos/owner/repo/rulesets"))
						.withRequestBody(
								// equalToJson rather than matchingJsonPath:
								// the latter's pattern class has no
								// reachability metadata, so it throws in the
								// native test image.
								equalToJson("""
										{
											"bypass_actors": [
												{
													"actor_id": 5,
													"actor_type": "Team",
													"bypass_mode": "always"
												}
											]
										}
										""", true, true)
						)
		);
	}

	private static RulesetDetailsResponse responseWith(
			String name,
			List<RulesetDetailsResponse.BypassActor> bypassActors,
			List<Rule> rules
	) {
		return new RulesetDetailsResponse(
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
				bypassActors,
				new RulesetDetailsResponse.Conditions(
						new RulesetDetailsResponse.Conditions.RefName(
								List.of(),
								List.of()
						),
						null,
						null,
						null
				),
				rules
		);
	}

	@Test
	void noDrift_whenUpdateAllowsFetchAndMergeMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.update(true)
								.updateAllowsFetchAndMerge(true)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(
						responseWith(
								"ci",
								null,
								List.of(
										new Rule.Update(
												new Rule.Update.Parameters(true)
										)
								)
						)
				),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsUpdateAllowsFetchAndMergeDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.update(true)
								.updateAllowsFetchAndMerge(true)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(
						responseWith(
								"ci",
								null,
								List.of(
										new Rule.Update(
												new Rule.Update.Parameters(
														false
												)
										)
								)
						)
				),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).singleElement()
				.satisfies(
						item -> assertThat(item.message()).contains(
								"rulesets.ci.update_allows_fetch_and_merge"
						)
				);
	}

	/**
	 * An Update rule with no parameters block reads as "fetch and merge not
	 * allowed" rather than as a null the comparison would choke on.
	 */
	@Test
	void updateRuleWithoutParameters_readsAsNotAllowed() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.update(true)
								.updateAllowsFetchAndMerge(false)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(
						responseWith("ci", null, List.of(new Rule.Update(null)))
				),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void noDrift_whenBypassActorsMatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.bypassActors(
										new BypassActorArgs(
												5L,
												RulesetDetailsResponse.BypassActor.ActorType.TEAM,
												RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
										)
								)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(
						responseWith(
								"ci",
								List.of(
										new RulesetDetailsResponse.BypassActor(
												5L,
												RulesetDetailsResponse.BypassActor.ActorType.TEAM,
												RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
										)
								),
								List.of()
						)
				),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsBypassActorDrift_whenActualHasNone() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.bypassActors(
										new BypassActorArgs(
												5L,
												RulesetDetailsResponse.BypassActor.ActorType.TEAM,
												RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS
										)
								)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(responseWith("ci", null, List.of())),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).singleElement()
				.satisfies(
						item -> assertThat(item.message())
								.contains("rulesets.ci.bypass_actors")
				);
	}

	@Test
	void noDrift_whenBothEmpty() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsExtraRuleset() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("ci")),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().message())
				.isEqualTo("rulesets.ci: extra (should not exist)");
	}

	@Test
	void detectsMissingRuleset() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(RulesetArgs.builder("ci").build())
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message())
				.isEqualTo("rulesets.ci: missing");
	}

	@Test
	void noDrift_whenRulesetsMatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(RulesetArgs.builder("ci").build())
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("ci")),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingIncludePattern() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.includePatterns("refs/heads/main")
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("ci")),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.path()).isEqualTo("rulesets.ci.include_patterns");
		assertThat(drift.missing()).hasSize(1);
		assertThat(drift.message()).contains("refs/heads/main");
	}

	@Test
	void detectsRequiredLinearHistoryDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.requiredLinearHistory(true)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("ci")),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"rulesets.ci.required_linear_history: want=true got=false"
		);
	}

	@Test
	void noDrift_whenRequiredLinearHistoryMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.requiredLinearHistory(true)
								.build()
				)
				.build();
		var actual = new RulesetDetailsResponse(
				1L,
				"ci",
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
						null,
						null,
						null
				),
				List.of(new Rule.RequiredLinearHistory())
		);
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(actual),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingStatusCheck() {
		var check = StatusCheckArgs.builder().context("build").build();
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.requiredStatusChecks(check)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("ci")),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.path())
				.isEqualTo("rulesets.ci.required_status_checks");
		assertThat(drift.missing()).hasSize(1);
	}

	@Test
	void detectsExtraAndMissingRuleset() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(RulesetArgs.builder("new-ruleset").build())
				.build();
		var group = new RulesetDriftGroup(
				ToDrifty.repository(desired).rulesets,
				List.of(matchingResponse("old-ruleset")),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(i -> i instanceof DriftItem.SectionMissing);
		assertThat(items).anyMatch(i -> i instanceof DriftItem.SectionExtra);
	}

}
