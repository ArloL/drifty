package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualTeam;

class TeamExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static ActualTeam team(String slug) {
		return new ActualTeam(
				1L,
				slug,
				slug,
				"",
				"closed",
				"notifications_enabled",
				null,
				Set.of(),
				Set.of()
		);
	}

	@Test
	void aTeamAtGitHubsDefaultsExportsNoMembers() {
		var field = (PklNode.Field) TeamExporter
				.entry(team("platform"), DEFAULTS.team());

		assertThat(field.name()).isEqualTo("platform");
		assertThat(PklWriter.write(field.value())).isEqualTo("");
	}

	@Test
	void aNameDifferingFromTheSlugIsEmitted() {
		var actual = new ActualTeam(
				1L,
				"platform",
				"Platform Team",
				"",
				"closed",
				"notifications_enabled",
				null,
				Set.of(),
				Set.of()
		);

		var field = (PklNode.Field) TeamExporter.entry(actual, DEFAULTS.team());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				name = "Platform Team"
				""");
	}

	@Test
	void privacyDifferingFromTheDefaultIsEmitted() {
		var base = team("platform");
		var actual = new ActualTeam(
				base.id(),
				base.slug(),
				base.name(),
				base.description(),
				"secret",
				base.notificationSetting(),
				base.parent(),
				base.members(),
				base.maintainers()
		);

		var field = (PklNode.Field) TeamExporter.entry(actual, DEFAULTS.team());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				privacy = "secret"
				""");
	}

	@Test
	void membersAreEmittedSortedRegardlessOfGitHubsOrder() {
		var base = team("platform");
		var actual = new ActualTeam(
				base.id(),
				base.slug(),
				base.name(),
				base.description(),
				base.privacy(),
				base.notificationSetting(),
				base.parent(),
				Set.of("zed", "amy"),
				base.maintainers()
		);

		var field = (PklNode.Field) TeamExporter.entry(actual, DEFAULTS.team());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				members {
				  "amy"
				  "zed"
				}
				""");
	}

}
