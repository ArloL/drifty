package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

class SecretScanningDelegatedBypassDriftGroupTest {

	@Test
	void noDriftWhenStatusAndReviewersMatch() {
		var desired = Desired.repository("repo")
				.withSecretScanningDelegatedBypass(true)
				.withSecretScanningDelegatedBypassReviewers(
						List.of(
								Desired.bypassReviewer(
										7L,
										Drifty.SecretScanningBypassReviewerType.TEAM
								)
						)
				);
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired.secretScanningDelegatedBypass,
				desired.secretScanningDelegatedBypassReviewers,
				true,
				List.of(new BypassReviewer("TEAM", 7L)),
				null,
				new RepoRef("owner", "repo")
		);

		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void detectsStatusDrift() {
		var desired = Desired.repository("repo")
				.withSecretScanningDelegatedBypass(true);
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired.secretScanningDelegatedBypass,
				desired.secretScanningDelegatedBypassReviewers,
				false,
				List.of(),
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
				"secret_scanning_delegated_bypass.enabled: want=true got=false"
		);
	}

	@Test
	void detectsReviewerDriftWhenEnabled() {
		var desired = Desired.repository("repo")
				.withSecretScanningDelegatedBypass(true)
				.withSecretScanningDelegatedBypassReviewers(
						List.of(
								Desired.bypassReviewer(
										7L,
										Drifty.SecretScanningBypassReviewerType.TEAM
								)
						)
				);
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired.secretScanningDelegatedBypass,
				desired.secretScanningDelegatedBypassReviewers,
				true,
				List.of(new BypassReviewer("ROLE", 9L)),
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
	}

	@Test
	void ignoresReviewersWhenDisabled() {
		var desired = Desired.repository("repo")
				.withSecretScanningDelegatedBypass(false);
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired.secretScanningDelegatedBypass,
				desired.secretScanningDelegatedBypassReviewers,
				false,
				List.of(new BypassReviewer("ROLE", 9L)),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect().getFirst().items()).isEmpty();
	}

}
