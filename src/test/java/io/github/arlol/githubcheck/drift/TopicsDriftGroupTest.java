package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TopicsDriftGroupTest {

	private static TopicsDriftGroup group(
			List<String> desired,
			List<String> actual
	) {
		return new TopicsDriftGroup(desired, actual, null, "owner", "repo");
	}

	@Test
	void noDrift_topicsMatch() {
		var items = group(List.of("java", "maven"), List.of("java", "maven"))
				.detect();
		assertThat(items).isEmpty();
	}

	@Test
	void detectsMissingTopics() {
		var items = group(List.of("java", "maven"), List.of("java")).detect();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.missing()).hasSize(1);
		assertThat(drift.extra()).isEmpty();
		assertThat(drift.message()).isEqualTo("topics missing: [maven]");
	}

	@Test
	void detectsExtraTopics() {
		var items = group(List.of(), List.of("stale-topic")).detect();
		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.missing()).isEmpty();
		assertThat(drift.extra()).hasSize(1);
		assertThat(drift.message()).isEqualTo("topics extra: [stale-topic]");
	}

}
