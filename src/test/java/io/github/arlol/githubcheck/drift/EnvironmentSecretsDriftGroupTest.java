package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;
import io.github.arlol.githubcheck.state.DriftyState;

class EnvironmentSecretsDriftGroupTest {

	private static Secret secret(String name, String updatedAt) {
		return new Secret(name, "2023-01-01T00:00:00Z", updatedAt);
	}

	private static List<DriftItem> items(EnvironmentSecretsDriftGroup group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
	}

	@Test
	void detectsExtraSecret_whenNoDesiredSecrets() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", _ -> {
				})
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of(
						"production",
						List.of(secret("EXTRA_SECRET", "2024-01-01T00:00:00Z"))
				),
				Map.of(),
				new DriftyState(),
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().path())
				.isEqualTo("environment.production.secrets.EXTRA_SECRET");
	}

	@Test
	void detectsMissingBaseline_whenSecretExistsWithoutRecordedBaseline() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-01-01T00:00:00Z"))
				),
				Map.of(),
				new DriftyState(),
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretMissingBaseline.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"environment.production.secrets.DB_PASS: exists but has no "
						+ "recorded baseline (--fix pushes the configured value)"
		);
	}

	@Test
	void detectsMissingSecret() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of("production", List.of()),
				Map.of(),
				new DriftyState(),
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message())
				.isEqualTo("environment.production.secrets.DB_PASS: missing");
	}

	@Test
	void detectsExtraSecret() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of(
						"production",
						List.of(
								secret("DB_PASS", "2024-01-01T00:00:00Z"),
								secret("STALE_KEY", "2024-01-01T00:00:00Z")
						)
				),
				Map.of(),
				new DriftyState(),
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SecretMissingBaseline && i.path()
						.equals("environment.production.secrets.DB_PASS")
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionExtra && i.path()
						.equals("environment.production.secrets.STALE_KEY")
		);
	}

	@Test
	void detectsPerItem_acrossMultipleEnvironments() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("staging", env -> env.secrets("STAGING_KEY"))
				.environment("production", env -> env.secrets("PROD_KEY"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of("staging", List.of(), "production", List.of()),
				Map.of(),
				new DriftyState(),
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionMissing && i.path()
						.equals("environment.staging.secrets.STAGING_KEY")
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionMissing && i.path()
						.equals("environment.production.secrets.PROD_KEY")
		);
	}

	@Test
	void noDrift_whenRecordedTimestampMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-01-01T00:00:00Z"))
				),
				Map.of(),
				state,
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsSecretChanged_whenTimestampMismatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-06-01T00:00:00Z"))
				),
				Map.of(),
				state,
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretChanged.class);
	}

	@Test
	void detectsSecretValueChanged_whenConfigValueChanged() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-01-01T00:00:00Z",
				state.hash("old-value")
		);
		var group = new EnvironmentSecretsDriftGroup(
				ToDrifty.repository(desired),
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-01-01T00:00:00Z"))
				),
				Map.of("repo-production-DB_PASS", "new-value"),
				state,
				null,
				"owner",
				"repo"
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretValueChanged.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"environment.production.secrets.DB_PASS: "
						+ "config value changed since last push"
		);
	}

}
