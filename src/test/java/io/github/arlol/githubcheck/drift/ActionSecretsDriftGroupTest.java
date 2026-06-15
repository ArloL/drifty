package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;
import io.github.arlol.githubcheck.state.DriftyState;

class ActionSecretsDriftGroupTest {

	private static Secret secret(String name, String updatedAt) {
		return new Secret(name, "2023-01-01T00:00:00Z", updatedAt);
	}

	private static ActionSecretsDriftGroup group(
			List<String> desiredSecrets,
			List<Secret> actualSecrets
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
			List<Secret> actualSecrets,
			Map<String, String> secretValues,
			DriftyState state
	) {
		var desired = RepositoryArgs.create("owner", "repo")
				.actionsSecrets(desiredSecrets.toArray(String[]::new))
				.build();
		return new ActionSecretsDriftGroup(
				ToDrifty.repository(desired),
				actualSecrets,
				secretValues,
				state,
				null,
				"owner",
				"repo"
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

}
