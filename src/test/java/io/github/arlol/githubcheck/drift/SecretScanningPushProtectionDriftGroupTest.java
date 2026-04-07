package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.config.RepositoryArgs;

class SecretScanningPushProtectionDriftGroupTest {

	@Test
	void noDriftWhenMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningPushProtection(true)
				.build();
		var group = new SecretScanningPushProtectionDriftGroup(
				desired,
				true,
				null,
				"owner",
				"repo"
		);

		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void detectsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningPushProtection(true)
				.build();
		var group = new SecretScanningPushProtectionDriftGroup(
				desired,
				false,
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
		assertThat(items.getFirst().message())
				.isEqualTo("enabled: want=true got=false");
	}

}
