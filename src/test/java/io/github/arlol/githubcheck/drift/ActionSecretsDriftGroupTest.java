package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class ActionSecretsDriftGroupTest {

	private static ActualSecret secret(String name, String updatedAt) {
		return new ActualSecret(name, updatedAt);
	}

	private static ActionSecretsDriftGroup group(
			List<String> desiredSecrets,
			List<ActualSecret> actualSecrets
	) {
		return group(
				desiredSecrets,
				actualSecrets,
				Map.of(),
				new DriftyState()
		);
	}

	private static ActionSecretsDriftGroup group(
			List<String> desiredSecrets,
			List<ActualSecret> actualSecrets,
			Map<String, String> secretValues,
			DriftyState state
	) {
		var desired = Desired.repository("repo")
				.withActionsSecrets(desiredSecrets);
		return new ActionSecretsDriftGroup(
				desired.actionsSecrets,
				actualSecrets,
				secretValues,
				state,
				null,
				new RepoRef("owner", "repo")
		);
	}

	private static List<DriftItem> items(ActionSecretsDriftGroup group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
	}

	@Test
	void noDrift_whenBothEmpty() {
		assertThat(group(List.of(), List.of()).detect()).isEmpty();
	}

	@Test
	void detectsMissingBaseline_whenSecretExistsWithoutRecordedBaseline() {
		var items = items(
				group(
						List.of("DEPLOY_KEY"),
						List.of(secret("DEPLOY_KEY", "2024-01-01T00:00:00Z"))
				)
		);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretMissingBaseline.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"action_secrets.DEPLOY_KEY: exists but has no recorded baseline (--fix pushes the configured value)"
		);
	}

	@Test
	void detectsMissingSecret() {
		var items = items(group(List.of("DEPLOY_KEY"), List.of()));

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message())
				.isEqualTo("action_secrets.DEPLOY_KEY: missing");
	}

	@Test
	void detectsExtraSecret() {
		var items = items(
				group(
						List.of(),
						List.of(secret("STALE_KEY", "2024-01-01T00:00:00Z"))
				)
		);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"action_secrets.STALE_KEY: extra (should not exist)"
		);
	}

	@Test
	void detectsPerItem_whenMissingAndExtra() {
		var items = items(
				group(
						List.of("NEW_KEY"),
						List.of(secret("OLD_KEY", "2024-01-01T00:00:00Z"))
				)
		);

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionMissing
						&& i.path().equals("action_secrets.NEW_KEY")
		);
		assertThat(items).anyMatch(
				i -> i instanceof DriftItem.SectionExtra
						&& i.path().equals("action_secrets.OLD_KEY")
		);
	}

	@Test
	void noDrift_whenRecordedTimestampMatches() {
		var state = new DriftyState();
		state.recordActionSecret(
				"repo",
				"DEPLOY_KEY",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);

		var group = group(
				List.of("DEPLOY_KEY"),
				List.of(secret("DEPLOY_KEY", "2024-01-01T00:00:00Z")),
				Map.of(),
				state
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsSecretChanged_whenTimestampMismatch() {
		var state = new DriftyState();
		state.recordActionSecret(
				"repo",
				"DEPLOY_KEY",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);

		var items = items(
				group(
						List.of("DEPLOY_KEY"),
						List.of(secret("DEPLOY_KEY", "2024-06-01T00:00:00Z")),
						Map.of(),
						state
				)
		);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretChanged.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"action_secrets.DEPLOY_KEY: changed outside drifty "
						+ "(recorded 2024-01-01T00:00:00Z, now 2024-06-01T00:00:00Z)"
		);
	}

	@Test
	void detectsSecretValueChanged_whenConfigValueChanged() {
		var state = new DriftyState();
		state.recordActionSecret(
				"repo",
				"DEPLOY_KEY",
				"2024-01-01T00:00:00Z",
				state.hash("old-value")
		);

		var items = items(
				group(
						List.of("DEPLOY_KEY"),
						List.of(secret("DEPLOY_KEY", "2024-01-01T00:00:00Z")),
						Map.of("repo-DEPLOY_KEY", "new-value"),
						state
				)
		);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretValueChanged.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"action_secrets.DEPLOY_KEY: config value changed since last push"
		);
	}

	@Test
	void noDrift_whenConfigValueMatchesRecordedHash() {
		var state = new DriftyState();
		state.recordActionSecret(
				"repo",
				"DEPLOY_KEY",
				"2024-01-01T00:00:00Z",
				state.hash("same-value")
		);

		var group = group(
				List.of("DEPLOY_KEY"),
				List.of(secret("DEPLOY_KEY", "2024-01-01T00:00:00Z")),
				Map.of("repo-DEPLOY_KEY", "same-value"),
				state
		);

		assertThat(group.detect()).isEmpty();
	}

	/** 32 zero bytes base64-encoded — a valid-length curve25519 public key. */
	private static final String TEST_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	/**
	 * Pushing the secret is only half the fix. GitHub never returns a secret's
	 * value, so the baseline drifty writes afterwards is the only thing that
	 * lets the next run tell "unchanged" from "rotated behind our back" — and
	 * losing it is silent: the push still succeeds, the report still says
	 * FIXED, and the run after that reports the secret all over again.
	 * <p>
	 * The end-to-end fix test does execute this path; it just never looked at
	 * the state afterwards, so deleting the record changed nothing it asserted.
	 */
	@Test
	void pushingASecretRecordsTheBaselineTheNextRunComparesAgainst(
			WireMockRuntimeInfo wm
	) {
		stubFor(
				get(urlEqualTo("/repos/owner/repo/actions/secrets/public-key"))
						.willReturn(okJson("""
								{"key_id": "123", "key": "%s"}
								""".formatted(TEST_PUBLIC_KEY)))
		);
		stubFor(
				put(urlEqualTo("/repos/owner/repo/actions/secrets/DEPLOY_KEY"))
						.willReturn(status(201))
		);
		stubFor(
				get(urlEqualTo("/repos/owner/repo/actions/secrets/DEPLOY_KEY"))
						.willReturn(okJson("""
								{
									"name": "DEPLOY_KEY",
									"created_at": "2024-01-01T00:00:00Z",
									"updated_at": "2024-06-01T00:00:00Z"
								}
								"""))
		);
		var state = new DriftyState();
		var desired = Desired.repository("repo")
				.withActionsSecrets(List.of("DEPLOY_KEY"));
		var group = new ActionSecretsDriftGroup(
				desired.actionsSecrets,
				List.of(),
				Map.of("repo-DEPLOY_KEY", "a-value"),
				state,
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect().getFirst().fix().execute().unfixedItems())
				.isEmpty();

		var record = state.actionSecretRecord("repo", "DEPLOY_KEY");
		assertThat(record).isNotNull();
		// The timestamp GitHub answered, so an out-of-band change moves it.
		assertThat(record.updatedAt()).isEqualTo("2024-06-01T00:00:00Z");
		// The hash of what drifty pushed, so rotating the config value shows.
		assertThat(record.valueHash()).isEqualTo(state.hash("a-value"));
	}

}
