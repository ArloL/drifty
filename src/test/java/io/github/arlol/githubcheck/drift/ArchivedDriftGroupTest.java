package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArchivedDriftGroupTest {

	@Test
	void noDrift_whenBothNotArchived() {
		var group = new ArchivedDriftGroup(false, false, null, "owner", "repo");
		assertThat(group.detect()).isEmpty();
	}

	@Test
	void noDrift_whenBothArchived() {
		var group = new ArchivedDriftGroup(true, true, null, "owner", "repo");
		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsDrift_whenDesiredArchivedActualNot() {
		var group = new ArchivedDriftGroup(true, false, null, "owner", "repo");
		var items = group.detect();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message())
				.isEqualTo("archived: want=true got=false");
	}

	@Test
	void detectsDrift_whenActualArchivedDesiredNot() {
		var group = new ArchivedDriftGroup(false, true, null, "owner", "repo");
		var items = group.detect();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message())
				.isEqualTo("archived: want=false got=true");
	}

}
