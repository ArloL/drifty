package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SchemaDefaultsTest {

	private static final String SCHEMA = Path.of("config/drifty.pkl")
			.toAbsolutePath()
			.toString();

	@Test
	void repositoryCarriesTheSchemasDefaults() {
		var defaults = SchemaDefaults.of(SCHEMA);

		assertThat(defaults.repository().hasIssues).isTrue();
		assertThat(defaults.repository().allowAutoMerge).isFalse();
		assertThat(defaults.repository().squashMergeCommitMessage.toString())
				.isEqualTo("COMMIT_MESSAGES");
	}

	@Test
	void organizationCarriesTheSchemasDefaults() {
		var defaults = SchemaDefaults.of(SCHEMA);

		assertThat(
				defaults.organization().defaultRepositoryPermission.toString()
		).isEqualTo("read");
		assertThat(defaults.organization().membersCanForkPrivateRepositories)
				.isFalse();
	}

	@Test
	void rulesetAndItsRulesCarryTheSchemasDefaults() {
		var defaults = SchemaDefaults.of(SCHEMA);

		assertThat(defaults.ruleset().enforcement.toString())
				.isEqualTo("active");
		assertThat(defaults.pullRequestRule().requiredApprovingReviewCount)
				.isZero();
		assertThat(defaults.mergeQueueRule().checkResponseTimeoutMinutes)
				.isEqualTo(60);
	}

}
