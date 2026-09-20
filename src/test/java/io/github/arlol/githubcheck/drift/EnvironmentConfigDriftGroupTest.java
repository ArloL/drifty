package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class EnvironmentConfigDriftGroupTest {

	private static final String POLICIES = "/repos/owner/repo/environments/production/deployment-branch-policies";

	/**
	 * Deployment branch policies are the one part of an environment
	 * {@code --fix} deletes, and nothing executed either side of it: the
	 * comparison ran, the fixes were built and thrown away. Deleting a policy
	 * is what takes deploy rights away from a branch, so "it was detected" is
	 * not the half worth testing.
	 * <p>
	 * Both patterns in one case on purpose. {@code policyRequest} splits
	 * {@code branch:main} on the colon and picks the type from the prefix, and
	 * a single-pattern fixture cannot tell a right answer from a type that is
	 * always BRANCH or a substring off by one.
	 */
	@Test
	void extraPoliciesAreDeletedAndMissingOnesCreatedWithTheirType(
			WireMockRuntimeInfo wm
	) {
		stubFor(post(urlEqualTo(POLICIES)).willReturn(okJson("""
				{"id": 99, "name": "main", "type": "branch"}
				""")));
		stubFor(
				delete(urlEqualTo(POLICIES + "/7"))
						.willReturn(aResponse().withStatus(204))
		);
		// The environment's own settings drift too — customBranchPolicies goes
		// on — and that fix runs in the same loop below.
		stubFor(
				put(urlEqualTo("/repos/owner/repo/environments/production"))
						.willReturn(okJson("{}"))
		);
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withCustomBranchPolicies(true)
										.withDeploymentBranchPatterns(
												List.of("main")
										)
										.withDeploymentTagPatterns(
												List.of("v*")
										)
						)
				);
		var actual = Map.of(
				"production",
				new ActualEnvironment(
						0,
						false,
						Set.of(),
						false,
						true,
						List.of(
								new ActualEnvironment.BranchPolicy(
										7L,
										"branch",
										"legacy"
								)
						)
				)
		);
		var group = new EnvironmentConfigDriftGroup(
				desired.environments,
				actual,
				false,
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				new RepoRef("owner", "repo")
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.contains(
						"environment_config.production.branch_policies.branch:legacy",
						"environment_config.production.branch_policies.branch:main",
						"environment_config.production.branch_policies.tag:v*"
				);
		for (DriftFix fix : fixes) {
			assertThat(fix.fix().execute().unfixedItems()).isEmpty();
		}

		verify(deleteRequestedFor(urlEqualTo(POLICIES + "/7")));
		verify(
				postRequestedFor(urlEqualTo(POLICIES)).withRequestBody(
						equalToJson(
								"{\"name\": \"main\", \"type\": \"branch\"}"
						)
				)
		);
		verify(
				postRequestedFor(urlEqualTo(POLICIES)).withRequestBody(
						equalToJson("{\"name\": \"v*\", \"type\": \"tag\"}")
				)
		);
	}

	@Test
	void noDriftWhenConfigMatches() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment().withWaitTimer(30)
						)
				);
		var actual = Map
				.of("production", new ActualEnvironment(30, false, false));
		var group = new EnvironmentConfigDriftGroup(
				desired.environments,
				actual,
				false,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

	@Test
	void detectsWaitTimerDrift() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment().withWaitTimer(30)
						)
				);
		var actual = Map
				.of("production", new ActualEnvironment(10, false, false));
		var group = new EnvironmentConfigDriftGroup(
				desired.environments,
				actual,
				false,
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
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path())
				.isEqualTo("environment_config.production.wait_timer");
		assertThat(drift.wanted()).isEqualTo(30);
		assertThat(drift.got()).isEqualTo(10);
	}

	@Test
	void detectsDeploymentBranchPolicyDrift() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withProtectedBranches(true)
										.withCustomBranchPolicies(false)
						)
				);
		var actual = Map
				.of("production", new ActualEnvironment(0, false, true));
		var group = new EnvironmentConfigDriftGroup(
				desired.environments,
				actual,
				false,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.FieldMismatch
						&& ((DriftItem.FieldMismatch) i).path()
								.equals(
										"environment_config.production.deployment_branch_policy.protected_branches"
								)
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.FieldMismatch
						&& ((DriftItem.FieldMismatch) i).path()
								.equals(
										"environment_config.production.deployment_branch_policy.custom_branch_policies"
								)
		);
	}

	@Test
	void detectsMissingEnvironment() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment().withWaitTimer(30)
						)
				);
		var actual = Map.<String, ActualEnvironment>of();

		var group = new EnvironmentConfigDriftGroup(
				desired.environments,
				actual,
				false,
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
		var drift = (DriftItem.SectionMissing) items.getFirst();
		assertThat(drift.path()).isEqualTo("environment_config.production");
	}

	private static List<DriftItem> items(EnvironmentConfigDriftGroup group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
	}

	private static EnvironmentConfigDriftGroup group(
			Drifty.Environment wanted,
			ActualEnvironment actual
	) {
		return new EnvironmentConfigDriftGroup(
				Map.of("production", wanted),
				actual == null ? Map.of() : Map.of("production", actual),
				false,
				null,
				new RepoRef("owner", "repo")
		);
	}

	@Test
	void detectsReviewerDrift() {
		var wanted = Desired.environment()
				.withReviewerUsers(List.of("alice"))
				.withReviewerTeams(List.of("ops"))
				.withPreventSelfReview(true);
		var actual = new ActualEnvironment(
				0,
				false,
				Set.of("User:bob"),
				false,
				false,
				List.of()
		);

		assertThat(items(group(wanted, actual))).extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"environment_config.production.prevent_self_review",
						"environment_config.production.reviewers"
				);
		assertThat(items(group(wanted, actual)))
				.filteredOn(i -> i.path().endsWith(".reviewers"))
				.singleElement()
				.satisfies(item -> {
					var drift = (DriftItem.SetDrift) item;
					assertThat(drift.missing()).extracting(Object::toString)
							.containsExactlyInAnyOrder(
									"User:alice",
									"Team:ops"
							);
					assertThat(drift.extra()).extracting(Object::toString)
							.containsExactly("User:bob");
				});
	}

	@Test
	void reviewersSomeoneAddedAreDriftAgainstAConfigThatNamesNone() {
		var actual = new ActualEnvironment(
				0,
				false,
				Set.of("User:bob"),
				false,
				false,
				List.of()
		);

		assertThat(items(group(Desired.environment(), actual)))
				.extracting(DriftItem::path)
				.containsExactly("environment_config.production.reviewers");
	}

	@Test
	void branchPoliciesAreComparedOnlyUnderCustomPolicies() {
		var wanted = Desired.environment()
				.withCustomBranchPolicies(true)
				.withDeploymentBranchPatterns(List.of("release/*"))
				.withDeploymentTagPatterns(List.of("v*"));
		var actual = new ActualEnvironment(
				0,
				false,
				Set.of(),
				false,
				true,
				List.of(
						new ActualEnvironment.BranchPolicy(
								1,
								"branch",
								"release/*"
						),
						new ActualEnvironment.BranchPolicy(
								2,
								"branch",
								"hotfix/*"
						)
				)
		);

		assertThat(items(group(wanted, actual))).extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"environment_config.production.branch_policies.tag:v*",
						"environment_config.production.branch_policies.branch:hotfix/*"
				);
		assertThat(items(group(wanted, actual)))
				.anyMatch(DriftItem.SectionMissing.class::isInstance)
				.anyMatch(DriftItem.SectionExtra.class::isInstance);

		var neither = new ActualEnvironment(0, false, false);
		assertThat(items(group(Desired.environment(), neither))).isEmpty();
	}

	@Test
	void aMissingEnvironmentWithPoliciesReportsEachPolicyMissingToo() {
		var wanted = Desired.environment()
				.withCustomBranchPolicies(true)
				.withDeploymentBranchPatterns(List.of("main"));

		assertThat(items(group(wanted, null))).extracting(DriftItem::path)
				.containsExactly(
						"environment_config.production",
						"environment_config.production.branch_policies.branch:main"
				);
	}

	@Test
	void extraEnvironmentsAreReportedAndNotDeleted() {
		var group = new EnvironmentConfigDriftGroup(
				Map.of(),
				Map.of("staging", new ActualEnvironment(0, false, false)),
				false,
				null,
				new RepoRef("owner", "repo")
		);

		var fixes = group.detect();
		assertThat(fixes).singleElement().satisfies(fix -> {
			assertThat(fix.items()).singleElement()
					.isInstanceOf(DriftItem.SectionExtra.class)
					.extracting(DriftItem::path)
					.isEqualTo("environment_config.staging");
			assertThat(fix.actionable()).isFalse();
			assertThat(fix.fix().execute().unfixedItems()).singleElement()
					.extracting(FixResult.Unfixed::reason)
					.asString()
					.contains("does not delete environments");
		});
	}

	@Test
	void githubPagesIsNotExtraWhenTheConfigDeclaresPages() {
		var group = new EnvironmentConfigDriftGroup(
				Map.of(),
				Map.of("github-pages", new ActualEnvironment(0, false, false)),
				true,
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(items(group)).isEmpty();
	}

	@Test
	void githubPagesIsExtraWhenTheConfigDeclaresNoPages() {
		var group = new EnvironmentConfigDriftGroup(
				Map.of(),
				Map.of("github-pages", new ActualEnvironment(0, false, false)),
				false,
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(items(group)).extracting(DriftItem::path)
				.containsExactly("environment_config.github-pages");
	}

	@Test
	void aDeclaredGithubPagesEnvironmentIsComparedLikeAnyOther() {
		var group = new EnvironmentConfigDriftGroup(
				Map.of("github-pages", Desired.environment().withWaitTimer(30)),
				Map.of("github-pages", new ActualEnvironment(0, false, false)),
				true,
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(items(group)).extracting(DriftItem::path)
				.containsExactly("environment_config.github-pages.wait_timer");
	}

}
