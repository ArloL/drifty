package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

class OrgActionSecretsDriftGroupTest {

	@Test
	void missingSecretIsDrift() {
		var group = new OrgActionSecretsDriftGroup(
				Map.of("PAT", Desired.orgSecret()),
				List.of(),
				Map.of(),
				Map.of("org-my-org-PAT", "value"),
				new DriftyState(),
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly("org_action_secrets.PAT");
	}

	@Test
	void visibilityDriftIsReported() {
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t1", state.hash("value"));
		var group = new OrgActionSecretsDriftGroup(
				Map.of(
						"PAT",
						Desired.orgSecret()
								.withVisibility(Drifty.SecretVisibility.ALL)
				),
				List.of(
						new ActualOrgSecret(
								"PAT",
								"t1",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				Map.of(),
				Map.of("org-my-org-PAT", "value"),
				state,
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly("org_action_secrets.PAT.visibility");
	}

	@Test
	void missingValueIsUnfixable() {
		var group = new OrgActionSecretsDriftGroup(
				Map.of("PAT", Desired.orgSecret()),
				List.of(),
				Map.of(),
				Map.of(),
				new DriftyState(),
				null,
				"my-org"
		);

		FixResult result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).singleElement()
				.satisfies(
						unfixed -> assertThat(unfixed.reason())
								.contains("org-my-org-PAT")
				);
	}

	@Test
	void verifiedSecretIsNoDrift() {
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t1", state.hash("value"));

		assertThat(
				group(
						Map.of("PAT", Desired.orgSecret()),
						List.of(secret("PAT", "t1", SecretVisibility.PRIVATE)),
						Map.of(),
						Map.of("org-my-org-PAT", "value"),
						state
				).detect()
		).isEmpty();
	}

	@Test
	void undeclaredSecretIsReportedButNeverDeleted() {
		var group = group(
				Map.of(),
				List.of(secret("STALE", "t1", SecretVisibility.PRIVATE)),
				Map.of(),
				Map.of(),
				new DriftyState()
		);

		DriftFix fix = group.detect().getFirst();
		assertThat(fix.items()).singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class)
				.extracting(DriftItem::path)
				.isEqualTo("org_action_secrets.STALE");
		assertThat(fix.fix().execute().unfixedItems()).singleElement()
				.satisfies(
						unfixed -> assertThat(unfixed.reason()).isEqualTo(
								"drifty does not delete secrets it did not create"
						)
				);
	}

	@Test
	void selectedRepositoriesAreCompared() {
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t1", state.hash("value"));

		var group = group(
				Map.of("PAT", selectedFor("one", "two")),
				List.of(
						new ActualOrgSecret(
								"PAT",
								"t1",
								SecretVisibility.SELECTED,
								List.of("one")
						)
				),
				Map.of(),
				Map.of("org-my-org-PAT", "value"),
				state
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_action_secrets.PAT.selected_repositories"
				);
	}

	/**
	 * A repository nobody can name is a secret that would be shared with fewer
	 * repositories than configured, so the push never happens.
	 */
	@Test
	void unknownSelectedRepositoryFailsTheFix() {
		var group = group(
				Map.of("PAT", selectedFor("one", "gone")),
				List.of(),
				Map.of("one", 1L),
				Map.of("org-my-org-PAT", "value"),
				new DriftyState()
		);

		FixResult result = group.detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).singleElement()
				.satisfies(
						unfixed -> assertThat(unfixed.reason())
								.isEqualTo("no repository gone in my-org")
				);
	}

	/**
	 * One PUT resolves the secret and its visibility, so both items hang off
	 * the same fix and a fix that cannot run reports both.
	 */
	@Test
	void secretAndVisibilityShareOneFix() {
		var group = group(
				Map.of(
						"PAT",
						Desired.orgSecret()
								.withVisibility(Drifty.SecretVisibility.ALL)
				),
				List.of(secret("PAT", "t1", SecretVisibility.PRIVATE)),
				Map.of(),
				Map.of(),
				new DriftyState()
		);

		List<DriftFix> fixes = group.detect();

		assertThat(fixes).singleElement()
				.extracting(DriftFix::items, list(DriftItem.class))
				.extracting(DriftItem::path)
				.containsExactly(
						"org_action_secrets.PAT",
						"org_action_secrets.PAT.visibility"
				);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).hasSize(2);
	}

	private static Drifty.OrgSecret selectedFor(String... repositories) {
		return Desired.orgSecret()
				.withVisibility(Drifty.SecretVisibility.SELECTED)
				.withSelectedRepositories(List.of(repositories));
	}

	private static ActualOrgSecret secret(
			String name,
			String updatedAt,
			SecretVisibility visibility
	) {
		return new ActualOrgSecret(name, updatedAt, visibility, List.of());
	}

	private static OrgActionSecretsDriftGroup group(
			Map<String, Drifty.OrgSecret> desired,
			List<ActualOrgSecret> actual,
			Map<String, Long> repositoryIds,
			Map<String, String> secretValues,
			DriftyState state
	) {
		return new OrgActionSecretsDriftGroup(
				desired,
				actual,
				repositoryIds,
				secretValues,
				state,
				null,
				"my-org"
		);
	}

}
