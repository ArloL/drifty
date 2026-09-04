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
	void isEmpty_isTrue_whenOnlyTheSaltWasGenerated() {
		var state = new DriftyState();
		state.hash("value");

		assertThat(state.isEmpty()).isTrue();
	}

	@Test
	void isEmpty_isFalse_afterRecordingAnActionSecret() {
		var state = new DriftyState();
		state.recordActionSecret("repo", "NAME", "2024-01-01T00:00:00Z", "abc");

		assertThat(state.isEmpty()).isFalse();
	}

	@Test
	void isEmpty_isFalse_afterRecordingAnEnvironmentSecret() {
		var state = new DriftyState();
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"NAME",
				"2024-01-01T00:00:00Z",
				"def"
		);

		assertThat(state.isEmpty()).isFalse();
	}

	@Test
	void orgAndRepoSecretsAreRecordedSeparately() {
		var state = new DriftyState();
		state.recordActionSecret("drifty", "PAT", "t1", "h1");
		state.recordOrgActionSecret("my-org", "PAT", "t2", "h2");

		assertThat(state.actionSecretRecord("drifty", "PAT").valueHash())
				.isEqualTo("h1");
		assertThat(state.orgActionSecretRecord("my-org", "PAT").valueHash())
				.isEqualTo("h2");
		assertThat(state.isEmpty()).isFalse();
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
