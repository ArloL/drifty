package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.RepoRef;

class ArchivedDriftGroupTest {

	@Test
	void noDrift_whenBothNotArchived() {
		var group = new ArchivedDriftGroup(
				false,
				false,
				null,
				new RepoRef("owner", "repo")
		);
		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void noDrift_whenBothArchived() {
		var group = new ArchivedDriftGroup(
				true,
				true,
				null,
				new RepoRef("owner", "repo")
		);
		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void detectsDrift_whenDesiredArchivedActualNot() {
		var group = new ArchivedDriftGroup(
				true,
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
				.isEqualTo("archived: want=true got=false");
	}

	@Test
	void detectsDrift_whenActualArchivedDesiredNot() {
		var group = new ArchivedDriftGroup(
				false,
				true,
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
				.isEqualTo("archived: want=false got=true");
	}

}
