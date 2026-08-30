package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;

class SecretScanningDelegatedAlertDismissalDriftGroupTest {

	@Test
	void noDriftWhenMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningDelegatedAlertDismissal(true)
				.build();
		var group = new SecretScanningDelegatedAlertDismissalDriftGroup(
				ToDrifty.repository(
						desired
				).secretScanningDelegatedAlertDismissal,
				true,
				null,
				new RepoRef("owner", "repo")
		);

		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void detectsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningDelegatedAlertDismissal(true)
				.build();
		var group = new SecretScanningDelegatedAlertDismissalDriftGroup(
				ToDrifty.repository(
						desired
				).secretScanningDelegatedAlertDismissal,
				false,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"secret_scanning_delegated_alert_dismissal.enabled: want=true got=false"
		);
	}

}
