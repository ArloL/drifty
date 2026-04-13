package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.config.RepositoryArgs;

class EnvironmentConfigDriftGroupTest {

	@Test
	void noDriftWhenNoDesiredEnvironments() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var actual = Map.of(
				"production",
				new EnvironmentDetailsResponse("production", List.of(), null)
		);
		var group = new EnvironmentConfigDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

	@Test
	void noDriftWhenConfigMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.waitTimer(30))
				.build();
		var actual = Map.of(
				"production",
				new EnvironmentDetailsResponse(
						"production",
						List.of(
								new EnvironmentDetailsResponse.ProtectionRule(
										EnvironmentDetailsResponse.ProtectionRuleType.WAIT_TIMER,
										30,
										null
								)
						),
						null
				)
		);
		var group = new EnvironmentConfigDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

	@Test
	void detectsWaitTimerDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.waitTimer(30))
				.build();
		var actual = Map.of(
				"production",
				new EnvironmentDetailsResponse(
						"production",
						List.of(
								new EnvironmentDetailsResponse.ProtectionRule(
										EnvironmentDetailsResponse.ProtectionRuleType.WAIT_TIMER,
										10,
										null
								)
						),
						null
				)
		);
		var group = new EnvironmentConfigDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("environment.production.wait_timer");
		assertThat(drift.wanted()).isEqualTo(30);
		assertThat(drift.got()).isEqualTo(10);
	}

	@Test
	void detectsDeploymentBranchPolicyDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment(
						"production",
						env -> env.deploymentBranchPolicy(true, false)
				)
				.build();
		var actual = Map.of(
				"production",
				new EnvironmentDetailsResponse(
						"production",
						List.of(),
						new EnvironmentDetailsResponse.DeploymentBranchPolicy(
								false,
								true
						)
				)
		);
		var group = new EnvironmentConfigDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
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
										"environment.production.deployment_branch_policy.protected_branches"
								)
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.FieldMismatch
						&& ((DriftItem.FieldMismatch) i).path()
								.equals(
										"environment.production.deployment_branch_policy.custom_branch_policies"
								)
		);
	}

	@Test
	void detectsMissingEnvironment() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.waitTimer(30))
				.build();
		var actual = Map.<String, EnvironmentDetailsResponse>of();

		var group = new EnvironmentConfigDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		var drift = (DriftItem.SectionMissing) items.getFirst();
		assertThat(drift.path()).isEqualTo("environment.production");
	}

}
