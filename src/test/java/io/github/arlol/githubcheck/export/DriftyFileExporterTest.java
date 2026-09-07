package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the file wrapper only: the header, the {@code amends} line, and
 * whether {@code users} appears at all. What goes inside each account's block
 * is {@link AccountExporterTest}'s job.
 */
class DriftyFileExporterTest {

	private static final Instant NOW = Instant.parse("2026-09-07T12:34:56Z");

	@Test
	void headerNamesTheVersionLoginsAndInstant_andAmendsTheSchema() {
		var organizations = List.<PklNode.Member>of(
				new PklNode.Field("acme", new PklNode.Obj(List.of()))
		);

		String file = DriftyFileExporter.file(
				"https://example.com/drifty.pkl",
				"1.2.3",
				NOW,
				organizations,
				List.of()
		);

		assertThat(file).isEqualTo(
				"""
						/// Exported by drifty 1.2.3 from acme on 2026-09-07T12:34:56Z.
						/// Only settings that differ from the schema defaults are listed; everything
						/// absent is at GitHub's default.
						amends "https://example.com/drifty.pkl"

						organizations {
						  ["acme"] {
						  }
						}
						"""
		);
	}

	@Test
	void emptyUsersListOmitsTheUsersBlockEntirely() {
		var organizations = List.<PklNode.Member>of(
				new PklNode.Field("acme", new PklNode.Obj(List.of()))
		);

		String file = DriftyFileExporter.file(
				"https://example.com/drifty.pkl",
				"1.2.3",
				NOW,
				organizations,
				List.of()
		);

		assertThat(file).doesNotContain("users");
		assertThat(file).isEqualTo(
				"""
						/// Exported by drifty 1.2.3 from acme on 2026-09-07T12:34:56Z.
						/// Only settings that differ from the schema defaults are listed; everything
						/// absent is at GitHub's default.
						amends "https://example.com/drifty.pkl"

						organizations {
						  ["acme"] {
						  }
						}
						"""
		);
	}

	@Test
	void aNonEmptyUsersListRendersAfterOrganizations() {
		var organizations = List.<PklNode.Member>of(
				new PklNode.Field("acme", new PklNode.Obj(List.of()))
		);
		var users = List.<PklNode.Member>of(
				new PklNode.Field("arlol", new PklNode.Obj(List.of()))
		);

		String file = DriftyFileExporter.file(
				"https://example.com/drifty.pkl",
				"1.2.3",
				NOW,
				organizations,
				users
		);

		assertThat(file).isEqualTo(
				"""
						/// Exported by drifty 1.2.3 from acme, arlol on 2026-09-07T12:34:56Z.
						/// Only settings that differ from the schema defaults are listed; everything
						/// absent is at GitHub's default.
						amends "https://example.com/drifty.pkl"

						organizations {
						  ["acme"] {
						  }
						}
						users {
						  ["arlol"] {
						  }
						}
						"""
		);
	}

}
