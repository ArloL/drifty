package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.pkl.Drifty;

class FetchFailuresTest {

	@Test
	void strictRethrows() {
		assertThatThrownBy(
				() -> FetchFailures.STRICT
						.read(Drifty.OrgGroupName.ORG_TEAMS, () -> {
							throw new GitHubApiException("HTTP 403 reading x");
						}, List.of())
		).isInstanceOf(GitHubApiException.class);
	}

	@Test
	void collectingRecordsTheFailureAndFallsBack() {
		var failures = FetchFailures.collecting();

		List<String> value = failures
				.read(Drifty.OrgGroupName.ORG_TEAMS, () -> {
					throw new GitHubApiException(
							"HTTP 403 reading /orgs/acme/teams: {\"message\":\"Forbidden\"}"
					);
				}, List.of());

		assertThat(value).isEmpty();
		assertThat(failures.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.group()).isEqualTo("org_teams");
			assertThat(failure.reason())
					.isEqualTo("HTTP 403 reading /orgs/acme/teams");
		});
	}

	@Test
	void aSuccessfulReadIsNotRecorded() {
		var failures = FetchFailures.collecting();

		assertThat(
				failures.read(
						Drifty.OrgGroupName.ORG_TEAMS,
						() -> List.of("platform"),
						List.of()
				)
		).containsExactly("platform");
		assertThat(failures.failures()).isEmpty();
	}

}
