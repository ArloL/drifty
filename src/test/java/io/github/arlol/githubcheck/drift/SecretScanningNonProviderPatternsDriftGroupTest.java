package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.config.RepositoryArgs;

class SecretScanningNonProviderPatternsDriftGroupTest {

	@Test
	void noDriftWhenMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningNonProviderPatterns(true)
				.build();
		var group = new SecretScanningNonProviderPatternsDriftGroup(
				desired,
				true,
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningNonProviderPatterns(true)
				.build();
		var group = new SecretScanningNonProviderPatternsDriftGroup(
				desired,
				false,
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message())
				.isEqualTo("enabled: want=true got=false");
	}

}
