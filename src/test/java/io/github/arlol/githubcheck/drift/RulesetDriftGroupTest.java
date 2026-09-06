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
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class RulesetDriftGroupTest {

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static ActualRuleset matchingResponse(String name) {
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
								null,
								null,
								null
						),
						List.of()
				)
		);
	}

	@Test
	void extraRulesetFix_deletesIt() {
		stubFor(
				delete(urlEqualTo("/repos/owner/repo/rulesets/1"))
						.willReturn(aResponse().withStatus(204))
		);
		var desired = Desired.repository("repo");
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withBypassActors(
												List.of(
														Desired.bypassActor(
																5L,
																Drifty.ActorType.TEAM,
																Drifty.BypassMode.ALWAYS
														)
												)
										)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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

	private static ActualRuleset responseWith(
			String name,
			List<RulesetDetailsResponse.BypassActor> bypassActors,
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
				)
		);
	}

	@Test
	void noDrift_whenUpdateAllowsFetchAndMergeMatches() {
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withUpdate(true)
										.withUpdateAllowsFetchAndMerge(true)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withUpdate(true)
										.withUpdateAllowsFetchAndMerge(true)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withUpdate(true)
										.withUpdateAllowsFetchAndMerge(false)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withBypassActors(
												List.of(
														Desired.bypassActor(
																5L,
																Drifty.ActorType.TEAM,
																Drifty.BypassMode.ALWAYS
														)
												)
										)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withBypassActors(
												List.of(
														Desired.bypassActor(
																5L,
																Drifty.ActorType.TEAM,
																Drifty.BypassMode.ALWAYS
														)
												)
										)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo");
		var group = new RulesetDriftGroup(
				desired.rulesets,
				List.of(),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsExtraRuleset() {
		var desired = Desired.repository("repo");
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(Map.of("ci", Desired.ruleset()));
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(Map.of("ci", Desired.ruleset()));
		var group = new RulesetDriftGroup(
				desired.rulesets,
				List.of(matchingResponse("ci")),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingIncludePattern() {
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withIncludePatterns(
												List.of("refs/heads/main")
										)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withRequiredLinearHistory(true)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withRequiredLinearHistory(true)
						)
				);
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
				desired.rulesets,
				List.of(ActualTypes.ruleset(actual)),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingStatusCheck() {
		var check = Desired.statusCheck("build");
		var desired = Desired.repository("repo")
				.withRulesets(
						Map.of(
								"ci",
								Desired.ruleset()
										.withRequiredStatusChecks(
												List.of(check)
										)
						)
				);
		var group = new RulesetDriftGroup(
				desired.rulesets,
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
		var desired = Desired.repository("repo")
				.withRulesets(Map.of("new-ruleset", Desired.ruleset()));
		var group = new RulesetDriftGroup(
				desired.rulesets,
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

	private static List<DriftItem> items(
			Drifty.Ruleset wanted,
			ActualRuleset actual
	) {
		return new RulesetDriftGroup(
				Map.of("rs", wanted),
				List.of(actual),
				null,
				new RepoRef("owner", "repo")
		).detect().stream().flatMap(f -> f.items().stream()).toList();
	}

	private static List<String> paths(
			Drifty.Ruleset wanted,
			ActualRuleset actual
	) {
		return items(wanted, actual).stream().map(DriftItem::path).toList();
	}

	@Test
	void detectsTargetEnforcementAndExcludePatterns() {
		var wanted = Desired.ruleset()
				.withTarget(Drifty.RulesetTarget.TAG)
				.withEnforcement(Drifty.RulesetEnforcement.EVALUATE)
				.withExcludePatterns(List.of("refs/tags/v0*"));

		assertThat(paths(wanted, matchingResponse("rs")))
				.containsExactlyInAnyOrder(
						"rulesets.rs.target",
						"rulesets.rs.enforcement",
						"rulesets.rs.exclude_patterns"
				);
	}

	@Test
	void aPullRequestRuleOnlyOneSideHasIsMissingOrExtra() {
		var withRule = responseWith(
				"rs",
				List.of(),
				List.of(
						new Rule.PullRequest(
								new Rule.PullRequest.Parameters(
										1,
										true,
										false,
										false,
										true,
										List.of("squash")
								)
						)
				)
		);

		assertThat(items(Desired.ruleset(), withRule)).singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class)
				.extracting(DriftItem::path)
				.isEqualTo("rulesets.rs.pull_request");
		assertThat(
				items(
						Desired.ruleset()
								.withPullRequest(Desired.pullRequestRule()),
						matchingResponse("rs")
				)
		).singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class)
				.extracting(DriftItem::path)
				.isEqualTo("rulesets.rs.pull_request");

		var wanted = Desired.ruleset()
				.withPullRequest(
						Desired.pullRequestRule()
								.withRequiredApprovingReviewCount(2L)
								.withRequireCodeOwnerReview(true)
								.withAllowedMergeMethods(
										List.of(Drifty.MergeMethod.SQUASH)
								)
				);
		assertThat(paths(wanted, withRule)).containsExactlyInAnyOrder(
				"rulesets.rs.pull_request.required_approving_review_count",
				"rulesets.rs.pull_request.dismiss_stale_reviews_on_push",
				"rulesets.rs.pull_request.require_code_owner_review",
				"rulesets.rs.pull_request.required_review_thread_resolution"
		);
	}

	@Test
	void mergeQueueWorkflowsAndFileRulesAreComparedWhenEitherSideHasThem() {
		var wanted = Desired.ruleset()
				.withMergeQueue(
						Desired.mergeQueueRule().withMaxEntriesToMerge(10L)
				)
				.withWorkflows(
						List.of(
								Desired.workflowRule(
										".github/workflows/ci.yml",
										7
								)
						)
				)
				.withFilePathRestrictions(List.of("secrets/*"))
				.withFileExtensionRestrictions(List.of("*.exe"))
				.withMaxFilePathLength(200L)
				.withMaxFileSize(50L);
		var actual = responseWith(
				"rs",
				List.of(),
				List.of(
						new Rule.MergeQueue(
								new Rule.MergeQueue.Parameters(
										60,
										"ALLGREEN",
										5,
										5,
										"MERGE",
										1,
										5
								)
						),
						new Rule.MaxFileSize(
								new Rule.MaxFileSize.Parameters(50)
						)
				)
		);

		assertThat(paths(wanted, actual)).containsExactlyInAnyOrder(
				"rulesets.rs.merge_queue.max_entries_to_merge",
				"rulesets.rs.workflows",
				"rulesets.rs.file_path_restrictions",
				"rulesets.rs.file_extension_restrictions",
				"rulesets.rs.max_file_path_length"
		);
		assertThat(paths(Desired.ruleset(), matchingResponse("rs"))).isEmpty();
	}

	@Test
	void strictStatusChecksAreComparedOnlyWithChecks() {
		var wanted = Desired.ruleset().withStrictRequiredStatusChecks(true);
		assertThat(paths(wanted, matchingResponse("rs"))).isEmpty();

		var withChecks = wanted.withRequiredStatusChecks(
				List.of(Desired.statusCheck("build"))
		);
		assertThat(paths(withChecks, matchingResponse("rs")))
				.containsExactlyInAnyOrder(
						"rulesets.rs.required_status_checks",
						"rulesets.rs.required_status_checks.strict"
				);
	}

}
