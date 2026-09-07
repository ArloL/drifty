package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PklWriterTest {

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
				)
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

	@Test
	void listingOfObjectsUsesNew() {
		var element = new PklNode.Obj(
				List.of(new PklNode.Field("name", PklNode.Scalar.of("api")))
		);
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"repositories",
								new PklNode.Listing(List.of(element))
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
				)
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
