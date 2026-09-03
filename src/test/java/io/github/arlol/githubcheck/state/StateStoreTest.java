package io.github.arlol.githubcheck.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

	private final StateStore store = new StateStore();

	@Test
	void load_returnsEmptyState_whenFileAbsent(@TempDir Path dir)
			throws Exception {
		var state = store.load(dir.resolve("missing.json"));
		assertThat(state.actionSecretRecord("repo", "NAME")).isNull();
	}

	@Test
	void save_thenLoad_roundTripsSecretRecords(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.recordActionSecret(
				"repo",
				"PAT",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		state.recordEnvironmentSecret(
				"repo",
				"production",
				"DB_PASS",
				"2024-02-01T00:00:00Z",
				state.hash("db")
		);
		store.save(path, state);

		var loaded = store.load(path);

		var action = loaded.actionSecretRecord("repo", "PAT");
		assertThat(action.updatedAt()).isEqualTo("2024-01-01T00:00:00Z");
		assertThat(action.valueHash()).isEqualTo(state.hash("value"));

		var env = loaded
				.environmentSecretRecord("repo", "production", "DB_PASS");
		assertThat(env.updatedAt()).isEqualTo("2024-02-01T00:00:00Z");
		assertThat(env.valueHash()).isEqualTo(state.hash("db"));
	}

	@Test
	void save_writesNoFile_whenStateRecordsNothing(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.hash("value");

		store.save(path, state);

		assertThat(path).doesNotExist();
	}

	@Test
	void save_leavesFileUntouched_whenStateIsUnchanged(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.recordActionSecret(
				"repo",
				"PAT",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		store.save(path, state);
		var before = FileTime.fromMillis(0);
		Files.setLastModifiedTime(path, before);

		store.save(path, store.load(path));

		assertThat(Files.getLastModifiedTime(path)).isEqualTo(before);
	}

	@Test
	void save_thenLoad_persistsSalt(@TempDir Path dir) throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		String hashed = state.hash("value");
		state.recordActionSecret("repo", "PAT", "2024-01-01T00:00:00Z", hashed);
		store.save(path, state);

		var loaded = store.load(path);

		assertThat(loaded.hash("value")).isEqualTo(hashed);
	}

}
