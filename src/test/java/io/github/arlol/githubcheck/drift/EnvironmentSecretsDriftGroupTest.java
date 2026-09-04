package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

class EnvironmentSecretsDriftGroupTest {

	private static ActualSecret secret(String name, String updatedAt) {
		return new ActualSecret(name, updatedAt);
	}

	private static List<DriftItem> items(EnvironmentSecretsDriftGroup group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
	}

	@Test
	void detectsExtraSecret_whenNoDesiredSecrets() {
		var desired = Desired.repository("repo")
				.withEnvironments(Map.of("production", Desired.environment()));
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of(
						"production",
						List.of(secret("EXTRA_SECRET", "2024-01-01T00:00:00Z"))
				),
				Map.of(),
				new DriftyState(),
				null,
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().path()).isEqualTo(
				"environment_secrets.production.secrets.EXTRA_SECRET"
		);
	}

	@Test
	void detectsMissingBaseline_whenSecretExistsWithoutRecordedBaseline() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("DB_PASS"))
						)
				);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-01-01T00:00:00Z"))
				),
				Map.of(),
				new DriftyState(),
				null,
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretMissingBaseline.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"environment_secrets.production.secrets.DB_PASS: exists but has no "
						+ "recorded baseline (--fix pushes the configured value)"
		);
	}

	@Test
	void detectsMissingSecret() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("DB_PASS"))
						)
				);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of("production", List.of()),
				Map.of(),
				new DriftyState(),
				null,
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"environment_secrets.production.secrets.DB_PASS: missing"
		);
	}

	@Test
	void detectsExtraSecret() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("DB_PASS"))
						)
				);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
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
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SecretMissingBaseline && i.path()
						.equals(
								"environment_secrets.production.secrets.DB_PASS"
						)
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionExtra && i.path()
						.equals(
								"environment_secrets.production.secrets.STALE_KEY"
						)
		);
	}

	@Test
	void detectsPerItem_acrossMultipleEnvironments() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"staging",
								Desired.environment()
										.withSecrets(List.of("STAGING_KEY")),
								"production",
								Desired.environment()
										.withSecrets(List.of("PROD_KEY"))
						)
				);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of("staging", List.of(), "production", List.of()),
				Map.of(),
				new DriftyState(),
				null,
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionMissing && i.path()
						.equals(
								"environment_secrets.staging.secrets.STAGING_KEY"
						)
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionMissing && i.path()
						.equals(
								"environment_secrets.production.secrets.PROD_KEY"
						)
		);
	}

	@Test
	void noDrift_whenRecordedTimestampMatches() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("DB_PASS"))
						)
				);
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-01-01T00:00:00Z"))
				),
				Map.of(),
				state,
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsSecretChanged_whenTimestampMismatch() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("DB_PASS"))
						)
				);
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-06-01T00:00:00Z"))
				),
				Map.of(),
				state,
				null,
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretChanged.class);
	}

	@Test
	void detectsSecretValueChanged_whenConfigValueChanged() {
		var desired = Desired.repository("repo")
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("DB_PASS"))
						)
				);
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-01-01T00:00:00Z",
				state.hash("old-value")
		);
		var group = new EnvironmentSecretsDriftGroup(
				desired.environments,
				Map.of(
						"production",
						List.of(secret("DB_PASS", "2024-01-01T00:00:00Z"))
				),
				Map.of("repo-production-DB_PASS", "new-value"),
				state,
				null,
				new RepoRef("owner", "repo")
		);

		var items = items(group);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretValueChanged.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"environment_secrets.production.secrets.DB_PASS: "
						+ "config value changed since last push"
		);
	}

}
