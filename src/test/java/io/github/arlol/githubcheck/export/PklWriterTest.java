package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PklWriterTest {

	/**
	 * {@code Field(name, Note)} is unreachable by design — a note belongs
	 * beside a field as its own sibling {@code Member}, never as one's value —
	 * so rendering it used to silently drop {@code name} and print only the
	 * note text. No exporter builds this shape on purpose; a future one that
	 * does should fail loudly rather than produce a file missing a field with
	 * no trace of why.
	 */
	@Test
	void aFieldWhoseValueIsANoteThrowsRatherThanDroppingTheName() {
		var field = new PklNode.Field(
				"secretScanning",
				new PklNode.Note("not actually how notes are meant to be used")
		);

		assertThatThrownBy(
				() -> PklWriter.write(new PklNode.Obj(List.of(field)))
		).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("secretScanning");
	}

	@Test
	void scalarsRenderByType() {
		var obj = new PklNode.Obj(
				List.of(
						new PklNode.Field("name", PklNode.Scalar.of("acme")),
						new PklNode.Field("count", PklNode.Scalar.of(3)),
						new PklNode.Field("on", PklNode.Scalar.of(true))
				)
		);

		assertThat(PklWriter.write(obj)).isEqualTo("""
				name = "acme"
				count = 3
				on = true
				""");
	}

	@Test
	void stringsAreEscaped() {
		var obj = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"description",
								PklNode.Scalar.of("a \"quoted\" \\ back\nslash")
						)
				)
		);

		assertThat(PklWriter.write(obj)).isEqualTo(
				"description = \"a \\\"quoted\\\" \\\\ back\\nslash\"\n"
		);
	}

	@Test
	void nestedObjectsIndent() {
		var inner = new PklNode.Obj(
				List.of(new PklNode.Field("waitTimer", PklNode.Scalar.of(30)))
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("environment", inner))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				environment {
				  waitTimer = 30
				}
				""");
	}

	@Test
	void mappingKeysAreQuoted() {
		var entry = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"privacy",
								PklNode.Scalar.of("secret")
						)
				)
		);
		var mapping = new PklNode.Mapping(
				List.of(new PklNode.Field("platform", entry))
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("teams", mapping))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				teams {
				  ["platform"] {
				    privacy = "secret"
				  }
				}
				""");
	}

	@Test
	void listingOfScalarsIsOnePerLine() {
		var listing = new PklNode.Listing(
				List.of(
						PklNode.Scalar.of("refs/heads/main"),
						PklNode.Scalar.of("refs/heads/release/*")
				),
				false
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("includePatterns", listing))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				includePatterns {
				  "refs/heads/main"
				  "refs/heads/release/*"
				}
				""");
	}

	/**
	 * {@code replace} is what {@code Fields.strings} sets: amending a listing
	 * adds to whatever the schema default already holds rather than replacing
	 * it, which is wrong for the one field in the schema whose default listing
	 * is non-empty ({@code Webhook.events}). See {@code Fields.strings}'s own
	 * javadoc.
	 */
	@Test
	void listingRenderedAsReplacementUsesEqualsNewListing() {
		var listing = new PklNode.Listing(
				List.of(PklNode.Scalar.of("pull_request")),
				true
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("events", listing))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				events = new Listing {
				  "pull_request"
				}
				""");
	}

	@Test
	void listingOfObjectsUsesNew() {
		var element = new PklNode.Obj(
				List.of(new PklNode.Field("name", PklNode.Scalar.of("api")))
		);
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"repositories",
								new PklNode.Listing(List.of(element), false)
						)
				)
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				repositories {
				  new {
				    name = "api"
				  }
				}
				""");
	}

	@Test
	void notesRenderAsCommentsAndWrapAtEightyColumns() {
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Note(
								"secret values are never returned by GitHub; supply them through DRIFTY_GITHUB_SECRETS"
						),
						new PklNode.Field("archived", PklNode.Scalar.of(true))
				)
		);

		assertThat(PklWriter.write(outer)).isEqualTo(
				"""
						// secret values are never returned by GitHub; supply them through
						// DRIFTY_GITHUB_SECRETS
						archived = true
						"""
		);
	}

	/**
	 * {@code pkl format} collapses an empty body onto the line that opens it,
	 * so the writer does too — an exported file is the first thing an adopter
	 * commits, and a formatting check on it is what issue #138 was.
	 */
	@Test
	void anEmptyBodyIsCollapsedOntoTheLineThatOpensIt() {
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"managed",
								new PklNode.Obj(List.of())
						),
						new PklNode.Field(
								"organizations",
								new PklNode.Mapping(List.of())
						),
						new PklNode.Field(
								"topics",
								new PklNode.Listing(List.of(), false)
						),
						new PklNode.Field(
								"events",
								new PklNode.Listing(List.of(), true)
						),
						new PklNode.Field(
								"rules",
								new PklNode.Listing(
										List.of(new PklNode.Obj(List.of())),
										false
								)
						)
				)
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				managed {}
				organizations {}
				topics {}
				events = new Listing {}
				rules {
				  new {}
				}
				""");
	}

	/**
	 * The other half of issue #138: {@code pkl format} moves the value of an
	 * assignment past a hundred columns onto its own line, indented one level
	 * past the name. A forked repository's description is the case that hit it.
	 */
	@Test
	void anAssignmentPastAHundredColumnsPutsItsValueOnTheNextLine() {
		var inner = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"description",
								PklNode.Scalar.of(
										"A GitHub Actions action that creates a new version using a CalVer-style derivative and pushes it"
								)
						)
				)
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("repository", inner))
		);

		assertThat(PklWriter.write(outer)).isEqualTo(
				"""
						repository {
						  description =
						    "A GitHub Actions action that creates a new version using a CalVer-style derivative and pushes it"
						}
						"""
		);
	}

	/**
	 * The boundary the formatter actually breaks at, measured against
	 * {@code pkl format} 0.32.1: a hundred columns fits, a hundred and one does
	 * not. Indentation and a mapping key both count towards it, which is why
	 * the two cases below are nested and keyed rather than bare.
	 */
	@Test
	void aHundredColumnsFitsAndAHundredAndOneDoesNot() {
		assertThat(PklWriter.write(keyed("a".repeat(85)))).isEqualTo(
				"""
						variables {
						  ["NAME"] = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
						}
						"""
		);
		assertThat(PklWriter.write(keyed("a".repeat(86)))).isEqualTo(
				"""
						variables {
						  ["NAME"] =
						    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
						}
						"""
		);
	}

	/**
	 * The width is counted over UTF-16 units, not characters — an emoji in a
	 * description counts as two, which is what {@code pkl format} counts too.
	 * Switching this to code points would leave a description full of them
	 * unwrapped and the file no longer formatter-clean.
	 */
	@Test
	void anEmojiCountsAsTheTwoUnitsTheFormatterCountsIt() {
		String fits = "\uD83D\uDE00".repeat(42) + "a";
		String doesNot = "\uD83D\uDE00".repeat(43);

		assertThat(PklWriter.write(keyed(fits)))
				.contains("[\"NAME\"] = \"" + fits + "\"");
		assertThat(PklWriter.write(keyed(doesNot)))
				.contains("[\"NAME\"] =\n    \"" + doesNot + "\"");
	}

	@Test
	void noteInsideMapping() {
		var entry = new PklNode.Obj(
				List.of(new PklNode.Field("value", PklNode.Scalar.of("a")))
		);
		var mapping = new PklNode.Mapping(
				List.of(
						new PklNode.Note("why this entry exists"),
						new PklNode.Field("key1", entry)
				)
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("data", mapping))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				data {
				  // why this entry exists
				  ["key1"] {
				    value = "a"
				  }
				}
				""");
	}

	@Test
	void noteInsideListing() {
		var listing = new PklNode.Listing(
				List.of(
						new PklNode.Note("items in the list"),
						PklNode.Scalar.of("item1"),
						PklNode.Scalar.of("item2")
				),
				false
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("items", listing))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				items {
				  // items in the list
				  "item1"
				  "item2"
				}
				""");
	}

	@Test
	void mappingKeyWithQuoteIsEscaped() {
		var entry = new PklNode.Obj(
				List.of(new PklNode.Field("val", PklNode.Scalar.of("x")))
		);
		var mapping = new PklNode.Mapping(
				List.of(new PklNode.Field("key\"with\"quotes", entry))
		);
		var outer = new PklNode.Obj(
				List.of(new PklNode.Field("data", mapping))
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				data {
				  ["key\\"with\\"quotes"] {
				    val = "x"
				  }
				}
				""");
	}

	private static PklNode keyed(String value) {
		var mapping = new PklNode.Mapping(
				List.of(new PklNode.Field("NAME", PklNode.Scalar.of(value)))
		);
		return new PklNode.Obj(
				List.of(new PklNode.Field("variables", mapping))
		);
	}

}
