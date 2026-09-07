package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
		assertThat(defaults.propertyCondition().source).isEqualTo("custom");
	}

	/**
	 * {@code URI.create} — the naive way to tell a URL from a filesystem path —
	 * throws on a space, which an ordinary home directory can contain. A schema
	 * path under a space-bearing directory must still evaluate.
	 */
	@Test
	void aSchemaPathWithASpaceStillEvaluates(@TempDir Path tempDir)
			throws IOException {
		Path spaced = Files.createDirectory(tempDir.resolve("my drifty"));
		Path copy = spaced.resolve("drifty.pkl");
		Files.copy(Path.of(SCHEMA), copy, StandardCopyOption.REPLACE_EXISTING);

		var defaults = SchemaDefaults.of(copy.toAbsolutePath().toString());

		assertThat(defaults.repository().hasIssues).isTrue();
	}

}
