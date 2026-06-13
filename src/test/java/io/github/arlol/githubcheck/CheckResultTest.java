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

}
