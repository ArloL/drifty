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

	@Test
	void emptyObjectRendersNothingButBraces() {
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Field("managed", new PklNode.Obj(List.of()))
				)
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				managed {
				}
				""");
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

}
