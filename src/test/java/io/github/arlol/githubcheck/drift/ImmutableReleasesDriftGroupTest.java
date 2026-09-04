package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.Desired;

class ImmutableReleasesDriftGroupTest {

	@Test
	void noDriftWhenMatches() {
		var desired = Desired.repository("repo").withImmutableReleases(true);
		var group = new ImmutableReleasesDriftGroup(
				desired.immutableReleases,
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
		var desired = Desired.repository("repo").withImmutableReleases(true);
		var group = new ImmutableReleasesDriftGroup(
				desired.immutableReleases,
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
				.isEqualTo("immutable_releases.enabled: want=true got=false");
	}

}
