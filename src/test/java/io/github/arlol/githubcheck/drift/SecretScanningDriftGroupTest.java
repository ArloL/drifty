package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;

class SecretScanningDriftGroupTest {

	@Test
	void noDriftWhenMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanning(true)
				.build();
		var group = new SecretScanningDriftGroup(
				ToDrifty.repository(desired).secretScanning,
				true,
				null,
				new RepoRef("owner", "repo")
		);

		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void detectsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanning(true)
				.build();
		var group = new SecretScanningDriftGroup(
				ToDrifty.repository(desired).secretScanning,
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
		assertThat(items.getFirst().message())
				.isEqualTo("secret_scanning.enabled: want=true got=false");
	}

}
