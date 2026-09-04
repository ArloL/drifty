package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.Desired;

class EnvironmentConfigDriftGroupTest {

	@Test
	void noDriftWhenNoDesiredEnvironments() {
		var desired = Desired.repository("repo");
		var actual = Map
				.of("production", new ActualEnvironment(0, false, false));
		var group = new EnvironmentConfigDriftGroup(
				desired.environments,
				actual,
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

}
