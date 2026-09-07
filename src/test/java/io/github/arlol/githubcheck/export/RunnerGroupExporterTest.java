package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualRunnerGroup;

class RunnerGroupExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static ActualRunnerGroup runnerGroup(
			String name,
			boolean isDefault
	) {
		return new ActualRunnerGroup(
				1L,
				name,
				"all",
				isDefault,
				false,
				false,
				Set.of(),
				List.of()
		);
	}

	@Test
	void aRunnerGroupAtGitHubsDefaultsExportsNoMembers() {
		var field = (PklNode.Field) RunnerGroupExporter
				.entry(runnerGroup("build", false), DEFAULTS.runnerGroup());

		assertThat(field.name()).isEqualTo("build");
		assertThat(PklWriter.write(field.value())).isEqualTo("");
	}

	@Test
	void visibilityDifferingFromTheDefaultIsEmitted() {
		var base = runnerGroup("build", false);
		var actual = new ActualRunnerGroup(
				base.id(),
				base.name(),
				"selected",
				base.isDefault(),
				base.allowsPublicRepositories(),
				base.restrictedToWorkflows(),
				base.selectedWorkflows(),
				List.of("service-a")
		);

		var field = (PklNode.Field) RunnerGroupExporter
				.entry(actual, DEFAULTS.runnerGroup());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				visibility = "selected"
				selectedRepositories {
				  "service-a"
				}
				""");
	}

	/**
	 * {@code isDefault} names no schema field, and {@code
	 * OrgRunnerGroupsDriftGroup} manages a default group like any other once it
	 * is named in the config — it is excluded only from the unlisted-extra
	 * report — so exporting it produces a section drifty can compare and fix,
	 * not one it would forever report as unfixable drift.
	 */
	@Test
	void theDefaultGroupExportsTheSameAsAnyOtherWhenItHasDrifted() {
		var actual = new ActualRunnerGroup(
				1L,
				"Default",
				"all",
				true,
				true,
				false,
				Set.of(),
				List.of()
		);

		var field = (PklNode.Field) RunnerGroupExporter
				.entry(actual, DEFAULTS.runnerGroup());

		assertThat(field.name()).isEqualTo("Default");
		assertThat(PklWriter.write(field.value())).isEqualTo("""
				allowsPublicRepositories = true
				""");
	}

}
