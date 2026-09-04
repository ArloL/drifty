package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.pkl.Drifty;

class ManagedGroupsTest {

	@Test
	void allExcept_managesEverythingNotNamed() {
		var managed = ManagedGroups.of(
				new Drifty.Managed(
						Drifty.ManageMode.ALL_EXCEPT,
						List.of(Drifty.GroupName.ACTION_SECRETS)
				)
		);

		assertThat(managed.manages(Drifty.GroupName.ACTION_SECRETS)).isFalse();
		assertThat(managed.manages(Drifty.GroupName.REPO_SETTINGS)).isTrue();
		assertThat(managed.unmanaged())
				.containsExactly(Drifty.GroupName.ACTION_SECRETS);
	}

	@Test
	void only_managesNothingElse() {
		var managed = ManagedGroups.of(
				new Drifty.Managed(
						Drifty.ManageMode.ONLY,
						List.of(Drifty.GroupName.REPO_SETTINGS)
				)
		);

		assertThat(managed.manages(Drifty.GroupName.REPO_SETTINGS)).isTrue();
		assertThat(managed.manages(Drifty.GroupName.ACTION_SECRETS)).isFalse();
		assertThat(managed.unmanaged())
				.hasSize(Drifty.GroupName.values().length - 1)
				.doesNotContain(Drifty.GroupName.REPO_SETTINGS);
	}

	@Test
	void emptyAllExcept_managesEverything() {
		var managed = ManagedGroups.of(
				new Drifty.Managed(Drifty.ManageMode.ALL_EXCEPT, List.of())
		);

		assertThat(managed.unmanaged()).isEmpty();
		for (Drifty.GroupName group : Drifty.GroupName.values()) {
			assertThat(managed.manages(group)).isTrue();
		}
	}

	@Test
	void all_managesEverything() {
		assertThat(ManagedGroups.all(Drifty.GroupName.class).unmanaged())
				.isEmpty();
	}

}
