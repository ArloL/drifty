package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.CheckResult.Entry;

class CheckResultTest {

	@Test
	void missingCount_countsMissingRepos() {
		var result = CheckResult.ofRepos(
				List.of(Entry.ok("a"), Entry.missing("b"), Entry.missing("c"))
		);
		assertThat(result.missingCount()).isEqualTo(2);
	}

	@Test
	void hasDrift_trueWhenRepoMissing() {
		var result = CheckResult
				.ofRepos(List.of(Entry.ok("a"), Entry.missing("b")));
		assertThat(result.hasDrift()).isTrue();
	}

	@Test
	void hasDrift_falseWhenOnlyOkAndUnknown() {
		var result = CheckResult
				.ofRepos(List.of(Entry.ok("a"), Entry.unknown("b")));
		assertThat(result.hasDrift()).isFalse();
	}

	@Test
	void drift_carriesFixPreview() {
		var r = Entry.drift(
				"a",
				List.of("description: want=Foo got="),
				List.of("repo_settings", "topics")
		);
		assertThat(r.fixPreview()).containsExactly("repo_settings", "topics");
	}

	@Test
	void drift_defaultsToEmptyFixPreview() {
		var r = Entry.drift("a", List.of("some diff"));
		assertThat(r.fixPreview()).isEmpty();
	}

	// ─── Fix reporting
	// ──────────────────────────────────────────────────

	@Test
	void fixReport_rendersFixedWithoutAReason() {
		var report = new CheckResult.FixReport(
				"vulnerability_alerts.enabled",
				true,
				null
		);
		assertThat(report.message())
				.isEqualTo("vulnerability_alerts.enabled: FIXED");
	}

	@Test
	void fixReport_rendersFailedWithItsReason() {
		var report = new CheckResult.FixReport(
				"immutable_releases.enabled",
				false,
				"HTTP 500"
		);
		assertThat(report.message())
				.isEqualTo("immutable_releases.enabled: FAILED (HTTP 500)");
	}

	@Test
	void fixed_isOkWhenNothingWasLeftUnfixed() {
		var r = Entry.fixed(
				"a",
				List.of(),
				List.of(new CheckResult.FixReport("topics", true, null))
		);
		assertThat(r.status()).isEqualTo(CheckResult.Status.OK);
		assertThat(r.diffs()).isEmpty();
	}

	@Test
	void fixed_isDriftWhenSomethingRemains() {
		var r = Entry.fixed(
				"a",
				List.of("topics missing: [java]"),
				List.of(new CheckResult.FixReport("topics", false, "HTTP 403"))
		);
		assertThat(r.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(r.diffs()).containsExactly("topics missing: [java]");
	}

	@Test
	void fixFailures_collectsFailuresAcrossReposAndNamesEach() {
		var result = CheckResult.ofRepos(
				List.of(
						Entry.fixed(
								"a",
								List.of("x"),
								List.of(
										new CheckResult.FixReport(
												"topics",
												true,
												null
										),
										new CheckResult.FixReport(
												"pages.build_type",
												false,
												"HTTP 403"
										)
								)
						),
						Entry.fixed(
								"b",
								List.of("y"),
								List.of(
										new CheckResult.FixReport(
												"archived",
												false,
												"HTTP 500"
										)
								)
						),
						Entry.ok("c")
				)
		);

		assertThat(result.fixFailures()).containsExactly(
				"a: pages.build_type: FAILED (HTTP 403)",
				"b: archived: FAILED (HTTP 500)"
		);
	}

	@Test
	void fixFailures_isEmptyWhenEveryFixSucceeded() {
		var result = CheckResult.ofRepos(
				List.of(
						Entry.fixed(
								"a",
								List.of(),
								List.of(
										new CheckResult.FixReport(
												"topics",
												true,
												null
										)
								)
						)
				)
		);
		assertThat(result.fixFailures()).isEmpty();
	}

	@Test
	void okCarriesUnmanagedGroups() {
		var result = Entry.ok("repo", List.of("action_secrets", "rulesets"));

		assertThat(result.unmanaged())
				.containsExactly("action_secrets", "rulesets");
	}

	@Test
	void unmanagedDefaultsToEmpty() {
		assertThat(Entry.ok("repo").unmanaged()).isEmpty();
	}

}
