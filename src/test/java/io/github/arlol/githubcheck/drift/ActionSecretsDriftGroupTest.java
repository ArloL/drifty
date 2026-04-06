package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.config.RepositoryArgs;

class ActionSecretsDriftGroupTest {

	private static ActionSecretsDriftGroup group(
			List<String> desiredSecrets,
			Set<String> actualSecrets
	) {
		var desired = RepositoryArgs.create("owner", "repo")
				.actionsSecrets(desiredSecrets.toArray(String[]::new))
				.build();
		return new ActionSecretsDriftGroup(
				desired,
				actualSecrets,
				Map.of(),
				null,
				"owner",
				"repo"
		);
	}

	@Test
	void noDrift_whenBothEmpty() {
		assertThat(group(List.of(), Set.of()).detect()).isEmpty();
	}

	@Test
	void noDrift_whenSecretsMatch() {
		assertThat(group(List.of("DEPLOY_KEY"), Set.of("DEPLOY_KEY")).detect())
				.isEmpty();
	}

	@Test
	void detectsMissingSecret() {
		var items = group(List.of("DEPLOY_KEY"), Set.of()).detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.missing()).hasSize(1);
		assertThat(drift.extra()).isEmpty();
		assertThat(drift.message())
				.isEqualTo("action_secrets missing: [DEPLOY_KEY]");
	}

	@Test
	void detectsExtraSecret() {
		var items = group(List.of(), Set.of("STALE_KEY")).detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.missing()).isEmpty();
		assertThat(drift.extra()).hasSize(1);
		assertThat(drift.message())
				.isEqualTo("action_secrets extra: [STALE_KEY]");
	}

	@Test
	void detectsMissingAndExtraSecrets() {
		var items = group(List.of("NEW_KEY"), Set.of("OLD_KEY")).detect();

		assertThat(items).hasSize(1);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.missing()).hasSize(1);
		assertThat(drift.extra()).hasSize(1);
	}

}
