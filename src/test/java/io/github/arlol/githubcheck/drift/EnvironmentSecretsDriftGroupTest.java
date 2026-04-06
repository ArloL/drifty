package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.config.RepositoryArgs;

class EnvironmentSecretsDriftGroupTest {

	@Test
	void noDrift_whenNoDesiredSecrets() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> {
				})
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				desired,
				Map.of("production", List.of("EXTRA_SECRET")),
				Map.of(),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsUnverifiable_whenSecretExists() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				desired,
				Map.of("production", List.of("DB_PASS")),
				Map.of(),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretUnverifiable.class);
		assertThat(items.getFirst().path())
				.isEqualTo("environment.production.secrets.DB_PASS");
		assertThat(items.getFirst().message()).isEqualTo(
				"environment.production.secrets.DB_PASS: unverifiable"
		);
	}

	@Test
	void detectsMissingSecret() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				desired,
				Map.of("production", List.of()),
				Map.of(),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().path())
				.isEqualTo("environment.production.secrets.DB_PASS");
		assertThat(items.getFirst().message())
				.isEqualTo("environment.production.secrets.DB_PASS: missing");
	}

	@Test
	void detectsExtraSecret() {
		var desired = RepositoryArgs.create("owner", "repo")
				.environment("production", env -> env.secrets("DB_PASS"))
				.build();
		var group = new EnvironmentSecretsDriftGroup(
				desired,
				Map.of("production", List.of("DB_PASS", "STALE_KEY")),
				Map.of(),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SecretUnverifiable && i.path()
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
				desired,
				Map.of("staging", List.of(), "production", List.of()),
				Map.of(),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

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

}
