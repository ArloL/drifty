package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

import org.junit.jupiter.api.Test;

/**
 * Covers the argument-, secret- and flag-handling helpers behind
 * {@link GitHubCheck#main(String[])}. {@code main} itself calls
 * {@link System#exit}, so it is exercised end to end by
 * {@code NativeExecutableIT} against the built binary instead.
 */
class GitHubCheckTest {

	// ─── optionValue
	// ────────────────────────────────────────────────────────────

	@Test
	void optionValue_returnsFollowingArgument() {
		assertThat(
				GitHubCheck.optionValue(
						List.of("--config", "custom.pkl", "--fix"),
						"--config"
				)
		).isEqualTo("custom.pkl");
	}

	@Test
	void optionValue_absentOption() {
		assertThat(GitHubCheck.optionValue(List.of("--fix"), "--config"))
				.isNull();
	}

	@Test
	void optionValue_optionIsLastArgument() {
		assertThat(
				GitHubCheck
						.optionValue(List.of("--fix", "--config"), "--config")
		).isNull();
	}

	// ─── parseGithubSecrets
	// ─────────────────────────────────────────────────────

	@Test
	void parseGithubSecrets_readsJsonObject() throws Exception {
		Map<String, String> secrets = GitHubCheck.parseGithubSecrets("""
				{"repo-TOKEN": "s3cret", "repo-prod-DEPLOY_KEY": "other"}
				""");

		assertThat(secrets).containsExactlyInAnyOrderEntriesOf(
				Map.of("repo-TOKEN", "s3cret", "repo-prod-DEPLOY_KEY", "other")
		);
	}

	@Test
	void parseGithubSecrets_nullOrBlankIsEmpty() throws Exception {
		assertThat(GitHubCheck.parseGithubSecrets(null)).isEmpty();
		assertThat(GitHubCheck.parseGithubSecrets("   ")).isEmpty();
	}

	// ─── collectMissingSecrets
	// ──────────────────────────────────────────────────

	private static DriftyConfig config(Drifty.Organization organization) {
		return new DriftyConfig(Map.of("my-org", organization), Map.of());
	}

	private static DriftyConfig config(Drifty.Repository... repositories) {
		return config(
				Desired.organization().withRepositories(List.of(repositories))
		);
	}

	private static Drifty.Repository repositoryWithSecrets() {
		return Desired.repository("repo")
				.withActionsSecrets(List.of("TOKEN"))
				.withEnvironments(
						Map.of(
								"prod",
								Desired.environment()
										.withSecrets(List.of("DEPLOY_KEY"))
						)
				);
	}

	@Test
	void collectMissingSecrets_noneMissing() {
		assertThat(
				GitHubCheck.collectMissingSecrets(
						config(repositoryWithSecrets()),
						Map.of("repo-TOKEN", "a", "repo-prod-DEPLOY_KEY", "b")
				)
		).isEmpty();
	}

	@Test
	void collectMissingSecrets_reportsActionAndEnvironmentSecrets() {
		assertThat(
				GitHubCheck.collectMissingSecrets(
						config(repositoryWithSecrets()),
						Map.of()
				)
		).containsExactlyInAnyOrder("repo-TOKEN", "repo-prod-DEPLOY_KEY");
	}

	@Test
	void collectMissingSecrets_reportsOrganizationSecretsUnderTheOrgPrefix() {
		var config = config(
				Desired.organization()
						.withActionsSecrets(Map.of("PAT", Desired.orgSecret()))
		);

		assertThat(GitHubCheck.collectMissingSecrets(config, Map.of()))
				.containsExactly("org-my-org-PAT");
		assertThat(
				GitHubCheck.collectMissingSecrets(
						config,
						Map.of("org-my-org-PAT", "a")
				)
		).isEmpty();
	}

	@Test
	void reportMissingSecrets_signalsWhetherAnythingIsMissing() {
		var config = config(
				Desired.repository("repo").withActionsSecrets(List.of("TOKEN"))
		);

		assertThat(GitHubCheck.reportMissingSecrets(config, Map.of())).isTrue();
		assertThat(
				GitHubCheck
						.reportMissingSecrets(config, Map.of("repo-TOKEN", "a"))
		).isFalse();
	}

	// ─── flags
	// ──────────────────────────────────────────────────────────────

	@Test
	void handledVersion_onlyForTheVersionFlag() {
		assertThat(GitHubCheck.handledVersion(new String[] { "--version" }))
				.isTrue();
		assertThat(GitHubCheck.handledVersion(new String[] { "--fix" }))
				.isFalse();
		assertThat(GitHubCheck.handledVersion(new String[] {})).isFalse();
		assertThat(
				GitHubCheck
						.handledVersion(new String[] { "--version", "extra" })
		).isFalse();
	}

	/**
	 * The same libsodium round trip the native binary runs; here it guards the
	 * exit code the JVM build produces.
	 */
	@Test
	void selfTest_succeedsOnTheJvm() {
		assertThat(GitHubCheck.selfTest()).isZero();
	}

}
