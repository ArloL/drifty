package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
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
	void secretWithoutARecordHasNoBaseline() {
		var items = items(
				group(
						Map.of("PAT", Desired.orgSecret()),
						List.of(secret("PAT", "t1", SecretVisibility.PRIVATE)),
						Map.of(),
						Map.of("org-my-org-PAT", "value"),
						new DriftyState()
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SecretMissingBaseline.class)
				.extracting(DriftItem::message)
				.isEqualTo(
						"org_action_secrets.PAT: exists but has no recorded baseline (--fix pushes the configured value)"
				);
	}

	@Test
	void newerTimestampIsAChangeOutsideDrifty() {
		var state = new DriftyState();
		state.recordOrgActionSecret(
				"my-org",
				"PAT",
				"2024-01-01T00:00:00Z",
				state.hash("value")
		);

		var items = items(
				group(
						Map.of("PAT", Desired.orgSecret()),
						List.of(
								secret(
										"PAT",
										"2024-06-01T00:00:00Z",
										SecretVisibility.PRIVATE
								)
						),
						Map.of(),
						Map.of("org-my-org-PAT", "value"),
						state
				)
		);

		// The message pins which timestamp is the recorded one: swapping the
		// two constructor arguments reads as plausibly as the right order.
		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SecretChanged.class)
				.extracting(DriftItem::message)
				.isEqualTo(
						"org_action_secrets.PAT: changed outside drifty "
								+ "(recorded 2024-01-01T00:00:00Z, now 2024-06-01T00:00:00Z)"
				);
	}

	@Test
	void rotatedConfigValueIsDrift() {
		var state = new DriftyState();
		state.recordOrgActionSecret("my-org", "PAT", "t1", state.hash("old"));

		var items = items(
				group(
						Map.of("PAT", Desired.orgSecret()),
						List.of(secret("PAT", "t1", SecretVisibility.PRIVATE)),
						Map.of(),
						Map.of("org-my-org-PAT", "new"),
						state
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SecretValueChanged.class)
				.extracting(DriftItem::message)
				.isEqualTo(
						"org_action_secrets.PAT: config value changed since last push"
				);
	}

	/**
	 * The report follows the config, not a hash order that changes with every
	 * JVM run. Four secrets, declared out of alphabetical order, so a shuffled
	 * map cannot pass by luck.
	 */
	@Test
	void secretsAreReportedInConfigOrder() {
		var desired = new LinkedHashMap<String, Drifty.OrgSecret>();
		desired.put("ZULU", Desired.orgSecret());
		desired.put("alpha", Desired.orgSecret());
		desired.put("MIKE", Desired.orgSecret());
		desired.put("bravo", Desired.orgSecret());

		var group = group(
				desired,
				List.of(),
				Map.of(),
				Map.of(),
				new DriftyState()
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_action_secrets.ZULU",
						"org_action_secrets.alpha",
						"org_action_secrets.MIKE",
						"org_action_secrets.bravo"
				);
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

	/**
	 * A repository list left behind under {@code private} is never sent — the
	 * PUT drops the ids for every visibility but {@code selected} — so it must
	 * not fail the push over a name nothing can resolve.
	 */
	@Test
	void privateSecretPushesDespiteAStaleRepositoryList(
			WireMockRuntimeInfo wm
	) {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets/public-key"))
						.willReturn(
								okJson(
										"""
												{
												  "key_id": "1",
												  "key": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
												}
												"""
								)
						)
		);
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/actions/secrets/PAT"))
						.willReturn(aResponse().withStatus(204))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets/PAT"))
						.willReturn(okJson("""
								{
								  "name": "PAT",
								  "updated_at": "t2",
								  "visibility": "private"
								}
								"""))
		);
		var state = new DriftyState();

		FixResult result = new OrgActionSecretsDriftGroup(
				Map.of(
						"PAT",
						Desired.orgSecret()
								.withSelectedRepositories(List.of("gone"))
				),
				List.of(),
				Map.of(),
				Map.of("org-my-org-PAT", "value"),
				state,
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				"my-org"
		).detect().getFirst().fix().execute();

		assertThat(result.unfixedItems()).isEmpty();
		assertThat(state.orgActionSecretRecord("my-org", "PAT").updatedAt())
				.isEqualTo("t2");
	}

	private static List<DriftItem> items(OrgActionSecretsDriftGroup group) {
		return group.detect()
				.stream()
				.flatMap(fix -> fix.items().stream())
				.toList();
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
