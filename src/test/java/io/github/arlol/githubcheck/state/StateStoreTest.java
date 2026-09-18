package io.github.arlol.githubcheck.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;

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
	void save_writesNoFile_whenLoadedRepositoriesHoldNoSecrets(
			@TempDir Path dir
	) throws Exception {
		var source = dir.resolve("source.json");
		Files.writeString(source, """
				{
				  "version": 1,
				  "salt": "abc",
				  "repositories": {
				    "repo": {
				      "action_secrets": {},
				      "environment_secrets": { "production": {} }
				    }
				  }
				}
				""");
		var path = dir.resolve("drifty-state.json");

		store.save(path, store.load(source));

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
	void save_overwritesFile_whenARecordChanged(@TempDir Path dir)
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

		state.recordActionSecret(
				"repo",
				"PAT",
				"2024-03-01T00:00:00Z",
				state.hash("rotated")
		);
		store.save(path, state);

		var record = store.load(path).actionSecretRecord("repo", "PAT");
		assertThat(record.updatedAt()).isEqualTo("2024-03-01T00:00:00Z");
		assertThat(record.valueHash()).isEqualTo(state.hash("rotated"));
	}

	@Test
	void stateFileWithoutOrganizations_stillLoads(@TempDir Path dir)
			throws Exception {
		Path file = dir.resolve("drifty-state.json");
		Files.writeString(file, """
				{
				  "version": 1,
				  "salt": "abcd",
				  "repositories": {
				    "drifty": {
				      "action_secrets": {
				        "PAT": {"updated_at": "t", "value_hash": "h"}
				      }
				    }
				  }
				}
				""");

		DriftyState state = new StateStore().load(file);

		assertThat(state.actionSecretRecord("drifty", "PAT")).isNotNull();
		assertThat(state.orgActionSecretRecord("my-org", "PAT")).isNull();
	}

	@Test
	void saveWritesOnlyOrgRecords(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t", state.hash("v"));

		new StateStore().save(file, state);

		assertThat(Files.readString(file)).contains("\"organizations\"");
	}

	/**
	 * Reading an organization record back is the only thing that makes Jackson
	 * construct an {@code OrgState}, and the native-image reachability metadata
	 * is generated from what the test suite traces. Without this round trip the
	 * generated metadata carries no constructor for it, and the native binary
	 * fails on the first state file that holds an org secret.
	 */
	@Test
	void save_thenLoad_roundTripsOrgSecretRecords(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.recordOrgActionSecret(
				"my-org",
				"NPM_TOKEN",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);
		store.save(path, state);

		var record = store.load(path)
				.orgActionSecretRecord("my-org", "NPM_TOKEN");

		assertThat(record.updatedAt()).isEqualTo("2024-01-01T00:00:00Z");
		assertThat(record.valueHash()).isEqualTo(state.hash("value"));
	}

	/**
	 * Same reason as the org round trip: the {@code webhook_secrets} field is
	 * only traced into the native-image metadata when a test reads it back.
	 */
	@Test
	void save_thenLoad_roundTripsWebhookSecretRecords(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.recordWebhookSecret(
				"repo",
				"ci",
				"2024-01-01T00:00:00Z",
				state.hash("hook")
		);
		state.recordOrgWebhookSecret(
				"my-org",
				"audit",
				"2024-02-01T00:00:00Z",
				state.hash("org-hook")
		);
		store.save(path, state);

		var loaded = store.load(path);

		assertThat(Files.readString(path)).contains("\"webhook_secrets\"");
		var repo = loaded.webhookSecretRecord("repo", "ci");
		assertThat(repo.updatedAt()).isEqualTo("2024-01-01T00:00:00Z");
		assertThat(repo.valueHash()).isEqualTo(state.hash("hook"));
		var org = loaded.orgWebhookSecretRecord("my-org", "audit");
		assertThat(org.updatedAt()).isEqualTo("2024-02-01T00:00:00Z");
		assertThat(org.valueHash()).isEqualTo(state.hash("org-hook"));
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

	/**
	 * Round-tripped here for the reason the secret records are: the native
	 * image's reflection metadata comes from what the suite traces, so a field
	 * no test serialises is missing from the shipped binary.
	 */
	@Test
	void save_thenLoad_roundTripsCacheEntries(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store(
				"/repos/owner/repo",
				"\"v1\"",
				"{\"name\":\"repo\"}",
				"<https://api.github.com/x?page=2>; rel=\"last\""
		);
		store.save(path, state);

		var loaded = store.load(path);

		var entry = loaded.lookup("/repos/owner/repo");
		assertThat(entry.etag()).isEqualTo("\"v1\"");
		assertThat(entry.body()).isEqualTo("{\"name\":\"repo\"}");
		assertThat(entry.link())
				.isEqualTo("<https://api.github.com/x?page=2>; rel=\"last\"");
	}

	/**
	 * A cache is worth writing down on its own. Before this, a run that
	 * recorded no secret wrote no file — and a cache that is never saved is
	 * never a cache.
	 */
	@Test
	void save_writesTheFile_whenOnlyTheCacheHasAnything(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store("/repos/owner/repo", "\"v1\"", "{}", null);

		store.save(path, state);

		assertThat(path).exists();
	}

	/**
	 * An entry costs about 1.5 KB and evicting one costs a charged request, so
	 * the window is set by what is worth remembering rather than by file size.
	 * A repository checked twice a year keeps its entry.
	 */
	@Test
	void save_dropsCacheEntriesNoRunHasConfirmedForHalfAYear(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store("/repos/owner/fresh", "\"f\"", "{}", null);
		state.store("/repos/owner/stale", "\"s\"", "{}", null);
		state.cache.put(
				"/repos/owner/stale",
				new DriftyState.CacheEntry(
						"\"s\"",
						"{}",
						null,
						LocalDate.now().minusDays(181).toString()
				)
		);

		store.save(path, state);
		var loaded = store.load(path);

		assertThat(loaded.lookup("/repos/owner/fresh")).isNotNull();
		assertThat(loaded.lookup("/repos/owner/stale")).isNull();
	}

	/** A date drifty cannot read is an entry it cannot trust to age out. */
	@Test
	void save_dropsACacheEntryWhoseDateIsUnreadable(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store("/repos/owner/keep", "\"k\"", "{}", null);
		state.cache.put(
				"/repos/owner/broken",
				new DriftyState.CacheEntry("\"b\"", "{}", null, "not-a-date")
		);

		store.save(path, state);

		assertThat(store.load(path).lookup("/repos/owner/broken")).isNull();
	}

}
