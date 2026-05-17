package io.github.arlol.githubcheck.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DriftyStateTest {

	@Test
	void hash_isStableForSameValue() {
		var state = new DriftyState();
		assertThat(state.hash("value")).isEqualTo(state.hash("value"));
	}

	@Test
	void hash_differsForDistinctValues() {
		var state = new DriftyState();
		assertThat(state.hash("one")).isNotEqualTo(state.hash("two"));
	}

	@Test
	void actionSecretRecord_isNull_whenNothingRecorded() {
		assertThat(new DriftyState().actionSecretRecord("repo", "NAME"))
				.isNull();
	}

	@Test
	void recordActionSecret_isReadBack() {
		var state = new DriftyState();
		state.recordActionSecret("repo", "NAME", "2024-01-01T00:00:00Z", "abc");

		var record = state.actionSecretRecord("repo", "NAME");
		assertThat(record).isNotNull();
		assertThat(record.updatedAt()).isEqualTo("2024-01-01T00:00:00Z");
		assertThat(record.valueHash()).isEqualTo("abc");
	}

	@Test
	void recordEnvironmentSecret_isReadBack() {
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"NAME",
				"2024-01-01T00:00:00Z",
				"def"
		);

		var record = state
				.environmentSecretRecord("repo", "production", "NAME");
		assertThat(record).isNotNull();
		assertThat(record.updatedAt()).isEqualTo("2024-01-01T00:00:00Z");
		assertThat(record.valueHash()).isEqualTo("def");
	}

	@Test
	void environmentSecretRecord_isNull_forUnknownEnvironment() {
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"NAME",
				"2024-01-01T00:00:00Z",
				"def"
		);
		assertThat(state.environmentSecretRecord("repo", "staging", "NAME"))
				.isNull();
	}

}
