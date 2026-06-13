package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.SecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis.BypassReviewer.ReviewerType;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.SecretScanningBypassReviewerArgs;

class SecretScanningDelegatedBypassDriftGroupTest {

	@Test
	void noDriftWhenStatusAndReviewersMatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningDelegatedBypass(true)
				.secretScanningDelegatedBypassReviewers(
						new SecretScanningBypassReviewerArgs(
								7L,
								ReviewerType.TEAM
						)
				)
				.build();
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired,
				true,
				List.of(new BypassReviewer(7L, ReviewerType.TEAM)),
				null,
				"owner",
				"repo"
		);

		var fixes = group.detect();
		assertThat(fixes).hasSize(1);
		assertThat(fixes.getFirst().items()).isEmpty();
	}

	@Test
	void detectsStatusDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningDelegatedBypass(true)
				.build();
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired,
				false,
				List.of(),
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message())
				.isEqualTo("enabled: want=true got=false");
	}

	@Test
	void detectsReviewerDriftWhenEnabled() {
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningDelegatedBypass(true)
				.secretScanningDelegatedBypassReviewers(
						new SecretScanningBypassReviewerArgs(
								7L,
								ReviewerType.TEAM
						)
				)
				.build();
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired,
				true,
				List.of(new BypassReviewer(9L, ReviewerType.ROLE)),
				null,
				"owner",
				"repo"
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
		var desired = RepositoryArgs.create("owner", "repo")
				.secretScanningDelegatedBypass(false)
				.build();
		var group = new SecretScanningDelegatedBypassDriftGroup(
				desired,
				false,
				List.of(new BypassReviewer(9L, ReviewerType.ROLE)),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect().getFirst().items()).isEmpty();
	}

}
