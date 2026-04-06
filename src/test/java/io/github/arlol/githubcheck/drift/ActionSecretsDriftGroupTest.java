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
	void detectsUnverifiable_whenSecretExists() {
		var items = group(List.of("DEPLOY_KEY"), Set.of("DEPLOY_KEY")).detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SecretUnverifiable.class);
		assertThat(items.getFirst().path())
				.isEqualTo("action_secrets.DEPLOY_KEY");
		assertThat(items.getFirst().message())
				.isEqualTo("action_secrets.DEPLOY_KEY: unverifiable");
	}

	@Test
	void detectsMissingSecret() {
		var items = group(List.of("DEPLOY_KEY"), Set.of()).detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().path())
				.isEqualTo("action_secrets.DEPLOY_KEY");
		assertThat(items.getFirst().message())
				.isEqualTo("action_secrets.DEPLOY_KEY: missing");
	}

	@Test
	void detectsExtraSecret() {
		var items = group(List.of(), Set.of("STALE_KEY")).detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().path())
				.isEqualTo("action_secrets.STALE_KEY");
		assertThat(items.getFirst().message()).isEqualTo(
				"action_secrets.STALE_KEY: extra (should not exist)"
		);
	}

	@Test
	void detectsPerItem_whenMissingAndExtra() {
		var items = group(List.of("NEW_KEY"), Set.of("OLD_KEY")).detect();

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

}
