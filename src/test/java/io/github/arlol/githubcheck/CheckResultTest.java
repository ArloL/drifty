package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.CheckResult.RepoCheckResult;

class CheckResultTest {

	@Test
	void missingCount_countsMissingRepos() {
		var result = new CheckResult(
				List.of(
						RepoCheckResult.ok("a"),
						RepoCheckResult.missing("b"),
						RepoCheckResult.missing("c")
				)
		);
		assertThat(result.missingCount()).isEqualTo(2);
	}

	@Test
	void hasDrift_trueWhenRepoMissing() {
		var result = new CheckResult(
				List.of(RepoCheckResult.ok("a"), RepoCheckResult.missing("b"))
		);
		assertThat(result.hasDrift()).isTrue();
	}

	@Test
	void hasDrift_falseWhenOnlyOkAndUnknown() {
		var result = new CheckResult(
				List.of(RepoCheckResult.ok("a"), RepoCheckResult.unknown("b"))
		);
		assertThat(result.hasDrift()).isFalse();
	}

	@Test
	void drift_carriesFixPreview() {
		var r = RepoCheckResult.drift(
				"a",
				List.of("description: want=Foo got="),
				List.of("repo_settings", "topics")
		);
		assertThat(r.fixPreview()).containsExactly("repo_settings", "topics");
	}

	@Test
	void drift_defaultsToEmptyFixPreview() {
		var r = RepoCheckResult.drift("a", List.of("some diff"));
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
		var r = RepoCheckResult.fixed(
				"a",
				List.of(),
				List.of(new CheckResult.FixReport("topics", true, null))
		);
		assertThat(r.status()).isEqualTo(CheckResult.Status.OK);
		assertThat(r.diffs()).isEmpty();
	}

	@Test
	void fixed_isDriftWhenSomethingRemains() {
		var r = RepoCheckResult.fixed(
				"a",
				List.of("topics missing: [java]"),
				List.of(new CheckResult.FixReport("topics", false, "HTTP 403"))
		);
		assertThat(r.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(r.diffs()).containsExactly("topics missing: [java]");
	}

	@Test
	void fixFailures_collectsFailuresAcrossReposAndNamesEach() {
		var result = new CheckResult(
				List.of(
						RepoCheckResult.fixed(
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
						RepoCheckResult.fixed(
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
						RepoCheckResult.ok("c")
				)
		);

		assertThat(result.fixFailures()).containsExactly(
				"a: pages.build_type: FAILED (HTTP 403)",
				"b: archived: FAILED (HTTP 500)"
		);
	}

	@Test
	void fixFailures_isEmptyWhenEveryFixSucceeded() {
		var result = new CheckResult(
				List.of(
						RepoCheckResult.fixed(
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
		var result = CheckResult.RepoCheckResult
				.ok("repo", List.of("action_secrets", "rulesets"));

		assertThat(result.unmanaged())
				.containsExactly("action_secrets", "rulesets");
	}

	@Test
	void unmanagedDefaultsToEmpty() {
		assertThat(CheckResult.RepoCheckResult.ok("repo").unmanaged())
				.isEmpty();
	}

}
