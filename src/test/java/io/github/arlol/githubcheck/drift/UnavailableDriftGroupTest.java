package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.pkl.Drifty;

class UnavailableDriftGroupTest {

	@Test
	void aFeatureTheConfigWantsOffIsNoDrift() {
		var group = new UnavailableDriftGroup(
				Drifty.GroupName.SECRET_SCANNING,
				false,
				"not offered"
		);

		assertThat(group.detect()).flatMap(DriftFix::items).isEmpty();
	}

	/**
	 * Wanting it on is reported, under the replaced group's own path, with the
	 * reason in the message — and never offered as something --fix would do.
	 */
	@Test
	void aFeatureTheConfigWantsOnIsReportedWithTheReasonAndNeverWritten() {
		var group = new UnavailableDriftGroup(
				Drifty.GroupName.SECRET_SCANNING,
				true,
				"not offered"
		);

		assertThat(group.detect()).singleElement().satisfies(fix -> {
			assertThat(fix.actionable()).isFalse();
			assertThat(fix.items()).singleElement()
					.satisfies(
							item -> assertThat(item.message()).isEqualTo(
									"secret_scanning.enabled: want=true unavailable: not offered"
							)
					);
			assertThat(fix.fix().execute().unfixedItems()).singleElement()
					.satisfies(unfixed -> {
						assertThat(unfixed.item().path())
								.isEqualTo("secret_scanning.enabled");
						assertThat(unfixed.reason()).isEqualTo("not offered");
					});
		});
	}

}
