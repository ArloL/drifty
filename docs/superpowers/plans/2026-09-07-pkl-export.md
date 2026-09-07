# Pkl Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `drifty --export <login>` writes `export.pkl`, a Pkl config listing every setting on an organization or personal account that differs from the schema defaults.

**Architecture:** The exporter reads state through the checkers' existing `fetchState` methods with every group managed, so there is no second read path and the `Actual*` records — already named the way the schema is — are what it renders. Each section has an exporter that emits a `PklNode` tree with one explicit `field(name, actual, default)` line per setting; a single `PklWriter` turns that tree into text. Defaults come from evaluating the schema at runtime, so a new field in `config/drifty.pkl` needs no change in the export code.

**Tech Stack:** Java 25, Maven, Pkl (pkl-config-java), JUnit 5, AssertJ, WireMock, GraalVM native-image.

**Spec:** `docs/superpowers/specs/2026-09-07-pkl-export-design.md`

## Global Constraints

- **Only non-default fields are emitted.** A field whose value equals the schema default is omitted; everything absent from the file is at GitHub's default.
- **A field with no schema default is always emitted.** `Repository.name`, `Webhook.url`, `CustomProperty.valueType`, `StatusCheck.context`, `BypassActor.actorId`/`actorType`/`bypassMode`, `RulePattern.operator`/`pattern`, `PropertyCondition.name`/`propertyValues`, `CodeScanningTool.tool`, `SecretScanningBypassReviewer.reviewerId`/`reviewerType`, `CodeSecurityBypassReviewer.reviewerId`/`reviewerType` — a config missing one fails to evaluate.
- **Default output path:** `./export.pkl`. `--out <path>` overrides. The file is overwritten without asking.
- **Amends URI, verbatim:** `https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl`. `--schema <uri>` overrides it, and overrides where defaults are evaluated from as well.
- **No `managed` block is ever emitted.** An export manages everything.
- **No reflection in production code.** The schema-coverage guard reflects over `Drifty.*`, and it lives in `src/test`, where reflection needs no native-image metadata.
- **Java 25.** Records, sealed interfaces, pattern matching in switch are all available and idiomatic here.
- **The build formats and lints for you.** `formatter-maven-plugin` rewrites sources during the build against `.settings/code-formatter-profile.xml` (tabs, 80 columns), and SpotBugs runs at `effort=Max threshold=Low`. Do not hand-format; do run the build before committing.
- **Iterate with `-DskipNativeTests`.** `./mvnw test` also builds the native test image when the JDK is GraalVM, which is slow. Run the full `./mvnw verify` once before pushing.

## File Structure

New production code, package `io.github.arlol.githubcheck.export`:

| File | Responsibility |
|---|---|
| `PklNode.java` | Sealed node tree: `Scalar`, `Obj`, `Mapping`, `Listing`, `Note`, `Member` |
| `PklWriter.java` | The only class that knows Pkl syntax: indentation, escaping, `=` vs `{` |
| `Fields.java` | `field`/`required`/`mapping`/`listing`/`note` helpers; where default comparison lives |
| `SchemaDefaults.java` | Evaluates the schema once; hands out `Drifty.*` instances carrying its defaults |
| `OrganizationExporter.java` | Org scalar settings, Actions permissions, workflow permissions |
| `RepositoryExporter.java` | Repository scalar settings and security flags |
| `RulesetExporter.java` | One ruleset, shared by both scopes |
| `BranchProtectionExporter.java` | One branch protection |
| `EnvironmentExporter.java` | One environment |
| `WebhookExporter.java` | One webhook |
| `TeamExporter.java` | One team |
| `CustomPropertyExporter.java` | One custom property definition |
| `CodeSecurityConfigurationExporter.java` | One code security configuration |
| `RunnerGroupExporter.java` | One runner group |
| `PagesExporter.java` | A repository's Pages config |
| `AccountExporter.java` | Assembles one `organizations`/`users` entry from its state |
| `DriftyFileExporter.java` | Assembles the module: header, `amends`, both account mappings |

New production code, package `io.github.arlol.githubcheck` (root, so package-private `fetchState` is reachable):

| File | Responsibility |
|---|---|
| `FetchFailures.java` | Records a group's read failure instead of throwing; `STRICT` rethrows |
| `ExportRunner.java` | Resolves each login to an account kind, fetches, exports, writes the file |

Modified: `GitHubCheck` (arguments and the `--export` branch), `OrganizationChecker` and `RepositoryChecker` (`FetchFailures`), `SPEC.md`, `README.md`, `FEATURES.md`, `CLAUDE.md`.

---

### Task 1: The node tree and the writer

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/PklNode.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/PklWriter.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/PklWriterTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `PklNode.Scalar.of(Object)`, `PklNode.Obj`, `PklNode.Mapping`, `PklNode.Listing`, `PklNode.Note`, `PklNode.Member` (a `Field(String name, PklNode value)` or a `Note`), and `PklWriter.write(PklNode)` returning `String`.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/arlol/githubcheck/export/PklWriterTest.java`:

```java
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

		assertThat(PklWriter.write(outer)).isEqualTo("""
				// secret values are never returned by GitHub; supply them through
				// DRIFTY_GITHUB_SECRETS
				archived = true
				""");
	}

	@Test
	void emptyObjectRendersNothingButBraces() {
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"managed",
								new PklNode.Obj(List.of())
						)
				)
		);

		assertThat(PklWriter.write(outer)).isEqualTo("""
				managed {
				}
				""");
	}

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=PklWriterTest -DskipNativeTests`
Expected: compilation failure — `PklNode` and `PklWriter` do not exist.

- [ ] **Step 3: Write the node tree**

`src/main/java/io/github/arlol/githubcheck/export/PklNode.java`:

```java
package io.github.arlol.githubcheck.export;

import java.util.List;

/**
 * A value in an exported Pkl file, one step above text.
 * <p>
 * Exporters build this tree and {@link PklWriter} is the only thing that turns
 * it into Pkl. Keeping syntax in one place is what lets a section exporter be
 * a list of field comparisons rather than a string builder, and it is why
 * indentation, escaping and the listing-versus-mapping distinction are each
 * decided once.
 */
public sealed interface PklNode {

	/** A member of an {@link Obj} or {@link Mapping}: a field or a comment. */
	sealed interface Member {
	}

	record Field(String name, PklNode value) implements Member {
	}

	/**
	 * A {@code //} comment. It carries what the export could not say — an
	 * unreadable group, an entity drifty does not manage, a secret value — so
	 * a reader does not mistake an absent section for an empty one.
	 */
	record Note(String text) implements Member, PklNode {
	}

	/** A rendered literal: {@code "text"}, {@code 3}, {@code true}. */
	record Scalar(String literal) implements PklNode {

		public static Scalar of(String value) {
			return new Scalar(quote(value));
		}

		public static Scalar of(boolean value) {
			return new Scalar(Boolean.toString(value));
		}

		public static Scalar of(long value) {
			return new Scalar(Long.toString(value));
		}

		/**
		 * An enum constant, as the string the schema spells it with. Every
		 * enum drifty generates from a Pkl union carries the union's own
		 * spelling in {@code toString}.
		 */
		public static Scalar of(Enum<?> value) {
			return of(value.toString());
		}

		private static String quote(String value) {
			var out = new StringBuilder("\"");
			for (char c : value.toCharArray()) {
				switch (c) {
					case '"' -> out.append("\\\"");
					case '\\' -> out.append("\\\\");
					case '\n' -> out.append("\\n");
					case '\r' -> out.append("\\r");
					case '\t' -> out.append("\\t");
					default -> out.append(c);
				}
			}
			return out.append('"').toString();
		}

	}

	record Obj(List<Member> members) implements PklNode {

		public Obj {
			members = List.copyOf(members);
		}

	}

	/** Keyed entries: {@code ["key"] { … }}. */
	record Mapping(List<Member> entries) implements PklNode {

		public Mapping {
			entries = List.copyOf(entries);
		}

	}

	/** Positional entries: a bare scalar, or {@code new { … }} for an object. */
	record Listing(List<PklNode> elements) implements PklNode {

		public Listing {
			elements = List.copyOf(elements);
		}

	}

}
```

- [ ] **Step 4: Write the writer**

`src/main/java/io/github/arlol/githubcheck/export/PklWriter.java`:

```java
package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link PklNode} tree as Pkl source.
 * <p>
 * The only place in the export that knows Pkl syntax. A {@code Mapping} entry
 * is keyed ({@code ["main"] { … }}) and a {@code Listing} element is not
 * ({@code new { … }}); getting that backwards is a file that does not
 * evaluate, so the distinction lives in the node type rather than in each of
 * the dozen exporters.
 */
public final class PklWriter {

	private static final int WIDTH = 80;
	private static final String INDENT = "  ";

	private PklWriter() {
	}

	public static String write(PklNode node) {
		var out = new StringBuilder();
		writeMembers(out, membersOf(node), 0);
		return out.toString();
	}

	private static List<PklNode.Member> membersOf(PklNode node) {
		return switch (node) {
			case PklNode.Obj obj -> obj.members();
			case PklNode.Mapping mapping -> mapping.entries();
			default -> throw new IllegalArgumentException(
					"the root of a file is an Obj or a Mapping, not " + node
			);
		};
	}

	private static void writeMembers(
			StringBuilder out,
			List<PklNode.Member> members,
			int depth
	) {
		for (PklNode.Member member : members) {
			switch (member) {
				case PklNode.Note note -> writeNote(out, note, depth);
				case PklNode.Field field -> writeField(out, field, depth, false);
			}
		}
	}

	private static void writeField(
			StringBuilder out,
			PklNode.Field field,
			int depth,
			boolean keyed
	) {
		String name = keyed ? "[\"" + field.name() + "\"]" : field.name();
		switch (field.value()) {
			case PklNode.Scalar scalar -> out.append(indent(depth))
					.append(name)
					.append(" = ")
					.append(scalar.literal())
					.append('\n');
			case PklNode.Note note -> writeNote(out, note, depth);
			case PklNode.Obj obj -> {
				out.append(indent(depth)).append(name).append(" {\n");
				writeMembers(out, obj.members(), depth + 1);
				out.append(indent(depth)).append("}\n");
			}
			case PklNode.Mapping mapping -> {
				out.append(indent(depth)).append(name).append(" {\n");
				writeMapping(out, mapping, depth + 1);
				out.append(indent(depth)).append("}\n");
			}
			case PklNode.Listing listing -> {
				out.append(indent(depth)).append(name).append(" {\n");
				writeListing(out, listing, depth + 1);
				out.append(indent(depth)).append("}\n");
			}
		}
	}

	private static void writeMapping(
			StringBuilder out,
			PklNode.Mapping mapping,
			int depth
	) {
		for (PklNode.Member entry : mapping.entries()) {
			switch (entry) {
				case PklNode.Note note -> writeNote(out, note, depth);
				case PklNode.Field field -> writeField(out, field, depth, true);
			}
		}
	}

	private static void writeListing(
			StringBuilder out,
			PklNode.Listing listing,
			int depth
	) {
		for (PklNode element : listing.elements()) {
			switch (element) {
				case PklNode.Scalar scalar -> out.append(indent(depth))
						.append(scalar.literal())
						.append('\n');
				case PklNode.Note note -> writeNote(out, note, depth);
				case PklNode.Obj obj -> {
					out.append(indent(depth)).append("new {\n");
					writeMembers(out, obj.members(), depth + 1);
					out.append(indent(depth)).append("}\n");
				}
				case PklNode.Mapping mapping -> {
					out.append(indent(depth)).append("new {\n");
					writeMapping(out, mapping, depth + 1);
					out.append(indent(depth)).append("}\n");
				}
				case PklNode.Listing nested -> {
					out.append(indent(depth)).append("new {\n");
					writeListing(out, nested, depth + 1);
					out.append(indent(depth)).append("}\n");
				}
			}
		}
	}

	/**
	 * A note, wrapped so a long reason does not run off the side of a file a
	 * developer is reading.
	 */
	private static void writeNote(
			StringBuilder out,
			PklNode.Note note,
			int depth
	) {
		String prefix = indent(depth) + "// ";
		for (String line : wrap(note.text(), WIDTH - prefix.length())) {
			out.append(prefix).append(line).append('\n');
		}
	}

	private static List<String> wrap(String text, int width) {
		var lines = new ArrayList<String>();
		var line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(word);
		}
		lines.add(line.toString());
		return lines;
	}

	private static String indent(int depth) {
		return INDENT.repeat(depth);
	}

}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=PklWriterTest -DskipNativeTests`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export src/test/java/io/github/arlol/githubcheck/export
git commit -m "Render a Pkl node tree

The export builds a tree and one writer turns it into text, so
indentation, string escaping and the listing-versus-mapping
distinction are each decided once instead of in every section."
```

---

### Task 2: The field helpers

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/Fields.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/FieldsTest.java`

**Interfaces:**
- Consumes: `PklNode` from Task 1.
- Produces: static methods on `Fields`, each returning `Optional<PklNode.Member>` except `note`:
  - `field(String name, String actual, String defaultValue)`
  - `field(String name, boolean actual, boolean defaultValue)`
  - `field(String name, long actual, long defaultValue)`
  - `field(String name, Integer actual, Integer defaultValue)` — null-tolerant on both sides
  - `field(String name, Enum<?> actual, String defaultValue)` — compares `actual.toString()`
  - `required(String name, String value)` / `required(String name, long value)`
  - `strings(String name, Collection<String> actual, Collection<String> defaultValue)` — order-insensitive comparison, sorted output
  - `objects(String name, List<PklNode> elements)` — omitted when empty
  - `mapping(String name, List<PklNode.Member> entries)` — omitted when empty
  - `nested(String name, List<PklNode.Member> members)` — omitted when empty
  - `note(String text)` returning `PklNode.Member`
  - `members(Optional<PklNode.Member>... candidates)` returning `List<PklNode.Member>` with the empties dropped

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/arlol/githubcheck/export/FieldsTest.java`:

```java
package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FieldsTest {

	@Test
	void aValueEqualToItsDefaultIsOmitted() {
		assertThat(Fields.field("description", "", "")).isEmpty();
		assertThat(Fields.field("hasIssues", true, true)).isEmpty();
	}

	@Test
	void aValueDifferentFromItsDefaultIsEmitted() {
		assertThat(Fields.field("description", "docs", ""))
				.contains(
						new PklNode.Field(
								"description",
								PklNode.Scalar.of("docs")
						)
				);
	}

	@Test
	void aRequiredFieldIsEmittedEvenWhenItLooksLikeADefault() {
		assertThat(Fields.required("name", ""))
				.contains(new PklNode.Field("name", PklNode.Scalar.of("")));
	}

	@Test
	void nullableIntegersCompareBothWays() {
		assertThat(Fields.field("maxFileSize", null, null)).isEmpty();
		assertThat(Fields.field("maxFileSize", 100, null)).isPresent();
		assertThat(Fields.field("maxFileSize", null, 100)).isPresent();
	}

	@Test
	void stringCollectionsCompareWithoutOrderAndEmitSorted() {
		assertThat(Fields.strings("topics", Set.of("b", "a"), Set.of("a", "b")))
				.isEmpty();
		assertThat(
				Fields.strings("topics", Set.of("b", "a"), Set.<String>of())
		)
				.contains(
						new PklNode.Field(
								"topics",
								new PklNode.Listing(
										List.of(
												PklNode.Scalar.of("a"),
												PklNode.Scalar.of("b")
										)
								)
						)
				);
	}

	@Test
	void emptyContainersAreOmitted() {
		assertThat(Fields.objects("repositories", List.of())).isEmpty();
		assertThat(Fields.mapping("teams", List.of())).isEmpty();
		assertThat(Fields.nested("pullRequest", List.of())).isEmpty();
	}

	@Test
	void membersDropsTheOmittedOnes() {
		List<PklNode.Member> members = Fields.members(
				Fields.field("description", "docs", ""),
				Fields.field("hasIssues", true, true)
		);

		assertThat(members).extracting(m -> ((PklNode.Field) m).name())
				.containsExactly("description");
	}

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=FieldsTest -DskipNativeTests`
Expected: compilation failure — `Fields` does not exist.

- [ ] **Step 3: Write the helpers**

`src/main/java/io/github/arlol/githubcheck/export/Fields.java`:

```java
package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds the members of an exported object, dropping everything that is
 * already at its schema default.
 * <p>
 * "Differs from the default" is decided here and nowhere else, so a section
 * exporter reads as a list of settings rather than a list of if-statements.
 * <p>
 * {@link #required} is the exception the schema forces: a field Pkl declares
 * without a default — {@code Repository.name}, {@code Webhook.url},
 * {@code CustomProperty.valueType} — has no default to differ from, and a file
 * that omits one does not evaluate.
 */
public final class Fields {

	private Fields() {
	}

	public static Optional<PklNode.Member> field(
			String name,
			String actual,
			String defaultValue
	) {
		return Objects.equals(actual, defaultValue) ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, PklNode.Scalar.of(actual))
				);
	}

	public static Optional<PklNode.Member> field(
			String name,
			boolean actual,
			boolean defaultValue
	) {
		return actual == defaultValue ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, PklNode.Scalar.of(actual))
				);
	}

	public static Optional<PklNode.Member> field(
			String name,
			long actual,
			long defaultValue
	) {
		return actual == defaultValue ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, PklNode.Scalar.of(actual))
				);
	}

	public static Optional<PklNode.Member> field(
			String name,
			Integer actual,
			Integer defaultValue
	) {
		if (Objects.equals(actual, defaultValue)) {
			return Optional.empty();
		}
		return Optional.of(
				new PklNode.Field(
						name,
						actual == null ? new PklNode.Scalar("null")
								: PklNode.Scalar.of(actual.longValue())
				)
		);
	}

	/**
	 * An enum against the string the schema defaults it to. Every enum
	 * generated from a Pkl union spells its constants the way the union does,
	 * so comparing the two as text is comparing like with like.
	 */
	public static Optional<PklNode.Member> field(
			String name,
			Enum<?> actual,
			String defaultValue
	) {
		return field(name, actual == null ? null : actual.toString(), defaultValue);
	}

	public static Optional<PklNode.Member> required(String name, String value) {
		return Optional
				.of(new PklNode.Field(name, PklNode.Scalar.of(value)));
	}

	public static Optional<PklNode.Member> required(String name, long value) {
		return Optional
				.of(new PklNode.Field(name, PklNode.Scalar.of(value)));
	}

	/**
	 * A collection of strings, compared as a set: GitHub's ordering is not
	 * drifty's, and the checker compares these unordered too. Emitted sorted
	 * so two exports of an unchanged account are identical files.
	 */
	public static Optional<PklNode.Member> strings(
			String name,
			Collection<String> actual,
			Collection<String> defaultValue
	) {
		if (new HashSet<>(actual).equals(new HashSet<>(defaultValue))) {
			return Optional.empty();
		}
		List<PklNode> elements = actual.stream()
				.sorted()
				.map(value -> (PklNode) PklNode.Scalar.of(value))
				.toList();
		return Optional
				.of(new PklNode.Field(name, new PklNode.Listing(elements)));
	}

	/** A listing of objects, omitted when empty — empty is its default. */
	public static Optional<PklNode.Member> objects(
			String name,
			List<PklNode> elements
	) {
		return elements.isEmpty() ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, new PklNode.Listing(elements))
				);
	}

	/** A keyed mapping, omitted when empty. */
	public static Optional<PklNode.Member> mapping(
			String name,
			List<PklNode.Member> entries
	) {
		return entries.isEmpty() ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, new PklNode.Mapping(entries))
				);
	}

	/** A nested object, omitted when nothing inside it drifted. */
	public static Optional<PklNode.Member> nested(
			String name,
			List<PklNode.Member> members
	) {
		return members.isEmpty() ? Optional.empty()
				: Optional.of(
						new PklNode.Field(name, new PklNode.Obj(members))
				);
	}

	public static PklNode.Member note(String text) {
		return new PklNode.Note(text);
	}

	@SafeVarargs
	public static List<PklNode.Member> members(
			Optional<PklNode.Member>... candidates
	) {
		return Stream.of(candidates).flatMap(Optional::stream).toList();
	}

	/** The same, for members built in a loop beside fixed ones. */
	public static List<PklNode.Member> concat(
			List<PklNode.Member> first,
			List<PklNode.Member> second
	) {
		var all = new ArrayList<>(first);
		all.addAll(second);
		return List.copyOf(all);
	}

}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=FieldsTest -DskipNativeTests`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export/Fields.java src/test/java/io/github/arlol/githubcheck/export/FieldsTest.java
git commit -m "Drop exported fields that are already at their default

One comparison decides what the file lists, so a section exporter is
a list of settings. required() is the schema's exception: a field
with no default has none to differ from."
```

---

### Task 3: Defaults from the schema

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/SchemaDefaults.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/SchemaDefaultsTest.java`

**Interfaces:**
- Consumes: `io.github.arlol.githubcheck.pkl.Drifty`.
- Produces: `SchemaDefaults.of(String schemaUri)` returning a `SchemaDefaults` with accessors `organization()`, `repository()`, `ruleset()`, `orgRuleset()`, `pullRequestRule()`, `mergeQueueRule()`, `branchProtection()`, `environment()`, `pages()`, `webhook()`, `customProperty()`, `codeSecurityConfiguration()`, `team()`, `runnerGroup()`, `orgSecret()`, `orgVariable()`, `actionsPermissions()`, `selectedActions()`. Also the constant `SchemaDefaults.MAIN_SCHEMA_URI`.

Read `src/test/java/io/github/arlol/githubcheck/testsupport/Desired.java` before writing this: it is the same idea in test scope and the accessor list mirrors it.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/arlol/githubcheck/export/SchemaDefaultsTest.java`:

```java
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

		assertThat(defaults.organization().defaultRepositoryPermission
				.toString()).isEqualTo("read");
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SchemaDefaultsTest -DskipNativeTests`
Expected: compilation failure — `SchemaDefaults` does not exist.

- [ ] **Step 3: Write the defaults loader**

Create `src/main/resources/export-defaults.pkl` — the module that instantiates one of everything:

```pkl
/// Instantiates every type the export diffs against, so the defaults come
/// from the schema rather than a second copy of it. `amends` is not enough:
/// a module that only amends the schema declares no accounts and hands out
/// nothing to compare against.
module exportDefaults

import "@schema@" as schema

organization: schema.Organization = new {}
repository: schema.Repository = new { name = "" }
ruleset: schema.Ruleset = new {}
orgRuleset: schema.OrgRuleset = new {}
pullRequestRule: schema.PullRequestRule = new {}
mergeQueueRule: schema.MergeQueueRule = new {}
branchProtection: schema.BranchProtection = new {}
environment: schema.Environment = new {}
pages: schema.Pages = new {}
webhook: schema.Webhook = new { url = "" }
customProperty: schema.CustomProperty = new { valueType = "string" }
codeSecurityConfiguration: schema.CodeSecurityConfiguration = new {}
team: schema.Team = new {}
runnerGroup: schema.RunnerGroup = new {}
orgSecret: schema.OrgSecret = new {}
orgVariable: schema.OrgVariable = new { value = "" }
actionsPermissions: schema.ActionsPermissions = new {}
selectedActions: schema.SelectedActions = new {}
```

Check the required fields of `RunnerGroup`, `OrgSecret`, `ActionsPermissions` and `SelectedActions` in `config/drifty.pkl` before writing this file and give each one a placeholder the way `webhook` and `customProperty` do above; a required field left unset fails evaluation with `Expected a value` naming the property.

`src/main/java/io/github/arlol/githubcheck/export/SchemaDefaults.java`:

```java
package io.github.arlol.githubcheck.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The schema's defaults, as {@code Drifty.*} instances.
 * <p>
 * Evaluated from the schema rather than restated in Java, so a field added to
 * {@code config/drifty.pkl} needs no change here — the same reason
 * {@code testsupport.Desired} builds its fixtures this way.
 * <p>
 * The URI is the one the exported file amends. Diffing against a different
 * copy of the schema than the file resolves to would put values in the file
 * that its own defaults already imply, or leave out ones they do not.
 */
public final class SchemaDefaults {

	public static final String MAIN_SCHEMA_URI = "https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl";

	private static final String TEMPLATE = "/export-defaults.pkl";

	private final Drifty.Organization organization;
	private final Drifty.Repository repository;
	private final Drifty.Ruleset ruleset;
	private final Drifty.OrgRuleset orgRuleset;
	private final Drifty.PullRequestRule pullRequestRule;
	private final Drifty.MergeQueueRule mergeQueueRule;
	private final Drifty.BranchProtection branchProtection;
	private final Drifty.Environment environment;
	private final Drifty.Pages pages;
	private final Drifty.Webhook webhook;
	private final Drifty.CustomProperty customProperty;
	private final Drifty.CodeSecurityConfiguration codeSecurityConfiguration;
	private final Drifty.Team team;
	private final Drifty.RunnerGroup runnerGroup;
	private final Drifty.OrgSecret orgSecret;
	private final Drifty.OrgVariable orgVariable;
	private final Drifty.ActionsPermissions actionsPermissions;
	private final Drifty.SelectedActions selectedActions;

	private SchemaDefaults(Config root) {
		organization = root.get("organization").as(Drifty.Organization.class);
		repository = root.get("repository").as(Drifty.Repository.class);
		ruleset = root.get("ruleset").as(Drifty.Ruleset.class);
		orgRuleset = root.get("orgRuleset").as(Drifty.OrgRuleset.class);
		pullRequestRule = root.get("pullRequestRule")
				.as(Drifty.PullRequestRule.class);
		mergeQueueRule = root.get("mergeQueueRule")
				.as(Drifty.MergeQueueRule.class);
		branchProtection = root.get("branchProtection")
				.as(Drifty.BranchProtection.class);
		environment = root.get("environment").as(Drifty.Environment.class);
		pages = root.get("pages").as(Drifty.Pages.class);
		webhook = root.get("webhook").as(Drifty.Webhook.class);
		customProperty = root.get("customProperty")
				.as(Drifty.CustomProperty.class);
		codeSecurityConfiguration = root.get("codeSecurityConfiguration")
				.as(Drifty.CodeSecurityConfiguration.class);
		team = root.get("team").as(Drifty.Team.class);
		runnerGroup = root.get("runnerGroup").as(Drifty.RunnerGroup.class);
		orgSecret = root.get("orgSecret").as(Drifty.OrgSecret.class);
		orgVariable = root.get("orgVariable").as(Drifty.OrgVariable.class);
		actionsPermissions = root.get("actionsPermissions")
				.as(Drifty.ActionsPermissions.class);
		selectedActions = root.get("selectedActions")
				.as(Drifty.SelectedActions.class);
	}

	/**
	 * @param schemaUri where the schema lives — the main-branch URL by
	 *                  default, a local path in tests. Substituted into the
	 *                  bundled template's import, so both the diff and the
	 *                  file's {@code amends} name the same schema.
	 */
	public static SchemaDefaults of(String schemaUri) {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			return new SchemaDefaults(
					evaluator.evaluate(
							ModuleSource.text(
									template().replace("@schema@", schemaUri)
							)
					)
			);
		}
	}

	private static String template() {
		try (var in = SchemaDefaults.class.getResourceAsStream(TEMPLATE)) {
			return new String(
					Objects.requireNonNull(in, TEMPLATE).readAllBytes(),
					StandardCharsets.UTF_8
			);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public Drifty.Organization organization() {
		return organization;
	}

	public Drifty.Repository repository() {
		return repository;
	}

	public Drifty.Ruleset ruleset() {
		return ruleset;
	}

	public Drifty.OrgRuleset orgRuleset() {
		return orgRuleset;
	}

	public Drifty.PullRequestRule pullRequestRule() {
		return pullRequestRule;
	}

	public Drifty.MergeQueueRule mergeQueueRule() {
		return mergeQueueRule;
	}

	public Drifty.BranchProtection branchProtection() {
		return branchProtection;
	}

	public Drifty.Environment environment() {
		return environment;
	}

	public Drifty.Pages pages() {
		return pages;
	}

	public Drifty.Webhook webhook() {
		return webhook;
	}

	public Drifty.CustomProperty customProperty() {
		return customProperty;
	}

	public Drifty.CodeSecurityConfiguration codeSecurityConfiguration() {
		return codeSecurityConfiguration;
	}

	public Drifty.Team team() {
		return team;
	}

	public Drifty.RunnerGroup runnerGroup() {
		return runnerGroup;
	}

	public Drifty.OrgSecret orgSecret() {
		return orgSecret;
	}

	public Drifty.OrgVariable orgVariable() {
		return orgVariable;
	}

	public Drifty.ActionsPermissions actionsPermissions() {
		return actionsPermissions;
	}

	public Drifty.SelectedActions selectedActions() {
		return selectedActions;
	}

}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=SchemaDefaultsTest -DskipNativeTests`
Expected: PASS, 3 tests. A failure naming a property means `export-defaults.pkl` left a required field unset — give it a placeholder.

- [ ] **Step 5: Register the template as a native-image resource**

The template is read from the classpath, so the production image needs it. In `src/test/java/io/github/arlol/githubcheck/ReachabilityMetadata.java`, add `"export-defaults.pkl"` to `MAIN_RESOURCE_PREFIXES` beside the Pkl entries, and add the resource entry to `src/main/resources/META-INF/native-image/reachability-metadata.json` by hand under `resources`:

```json
{ "glob": "export-defaults.pkl" }
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export/SchemaDefaults.java src/main/resources/export-defaults.pkl src/test/java/io/github/arlol/githubcheck/export/SchemaDefaultsTest.java src/main/resources/META-INF/native-image/reachability-metadata.json src/test/java/io/github/arlol/githubcheck/ReachabilityMetadata.java
git commit -m "Read the export's defaults out of the schema

Evaluated from the same schema URI the exported file amends, so a
field added to config/drifty.pkl needs no change here and the diff
cannot disagree with what the file resolves to."
```

---

### Task 4: Organization scalar settings

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/OrganizationExporter.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/OrganizationExporterTest.java`

**Interfaces:**
- Consumes: `Fields`, `PklNode`, `SchemaDefaults`, `io.github.arlol.githubcheck.actual.ActualOrganization`.
- Produces: `OrganizationExporter.settings(ActualOrganization actual, Drifty.Organization defaults)` returning `List<PklNode.Member>`.

The mapping, in order. Every left-hand name is a field on `Drifty.Organization`; every accessor is on `ActualOrganization`. The first block is writable, the second is the check-only block that gets a note instead of a field.

| Schema field | Actual accessor |
|---|---|
| `displayName` | `displayName()` |
| `description` | `description()` |
| `websiteUrl` | `websiteUrl()` |
| `company` | `company()` |
| `email` | `email()` |
| `location` | `location()` |
| `twitterUsername` | `twitterUsername()` |
| `hasOrganizationProjects` | `hasOrganizationProjects()` |
| `hasRepositoryProjects` | `hasRepositoryProjects()` |
| `defaultRepositoryPermission` | `defaultRepositoryPermission()` |
| `membersCanCreateRepositories` | `membersCanCreateRepositories()` |
| `membersCanCreatePublicRepositories` | `membersCanCreatePublicRepositories()` |
| `membersCanCreatePrivateRepositories` | `membersCanCreatePrivateRepositories()` |
| `membersCanCreateInternalRepositories` | `membersCanCreateInternalRepositories()` |
| `membersCanCreatePages` | `membersCanCreatePages()` |
| `membersCanCreatePublicPages` | `membersCanCreatePublicPages()` |
| `membersCanCreatePrivatePages` | `membersCanCreatePrivatePages()` |
| `membersCanForkPrivateRepositories` | `membersCanForkPrivateRepositories()` |
| `webCommitSignoffRequired` | `webCommitSignoffRequired()` |
| `deployKeysEnabledForRepositories` | `deployKeysEnabledForRepositories()` |

Check-only — emitted as a note naming the setting and its value, never as a field:

| Schema field | Actual accessor |
|---|---|
| `defaultRepositoryBranch` | `defaultRepositoryBranch()` |
| `twoFactorRequirementEnabled` | `twoFactorRequirementEnabled()` |
| `membersCanDeleteRepositories` | `membersCanDeleteRepositories()` |
| `membersCanChangeRepoVisibility` | `membersCanChangeRepoVisibility()` |
| `membersCanInviteOutsideCollaborators` | `membersCanInviteOutsideCollaborators()` |
| `membersCanDeleteIssues` | `membersCanDeleteIssues()` |
| `membersCanCreateTeams` | `membersCanCreateTeams()` |
| `membersCanViewDependencyInsights` | `membersCanViewDependencyInsights()` |
| `readersCanCreateDiscussions` | `readersCanCreateDiscussions()` |
| `displayCommenterFullNameSettingEnabled` | `displayCommenterFullNameSettingEnabled()` |

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/arlol/githubcheck/export/OrganizationExporterTest.java`:

```java
package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.testsupport.Actual;

class OrganizationExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	@Test
	void anUntouchedOrganizationExportsNothing() {
		List<PklNode.Member> members = OrganizationExporter
				.settings(Actual.organization(), DEFAULTS.organization());

		assertThat(members).isEmpty();
	}

	@Test
	void aChangedSettingIsExported() {
		var actual = Actual.organization();
		var changed = new io.github.arlol.githubcheck.actual.ActualOrganization(
				"Acme Inc",
				actual.description(),
				actual.websiteUrl(),
				actual.company(),
				actual.email(),
				actual.location(),
				actual.twitterUsername(),
				actual.hasOrganizationProjects(),
				actual.hasRepositoryProjects(),
				actual.defaultRepositoryPermission(),
				actual.membersCanCreateRepositories(),
				actual.membersCanCreatePublicRepositories(),
				actual.membersCanCreatePrivateRepositories(),
				actual.membersCanCreateInternalRepositories(),
				actual.membersCanCreatePages(),
				actual.membersCanCreatePublicPages(),
				actual.membersCanCreatePrivatePages(),
				actual.membersCanForkPrivateRepositories(),
				actual.webCommitSignoffRequired(),
				actual.deployKeysEnabledForRepositories(),
				actual.defaultRepositoryBranch(),
				actual.twoFactorRequirementEnabled(),
				actual.membersCanDeleteRepositories(),
				actual.membersCanChangeRepoVisibility(),
				actual.membersCanInviteOutsideCollaborators(),
				actual.membersCanDeleteIssues(),
				actual.membersCanCreateTeams(),
				actual.membersCanViewDependencyInsights(),
				actual.readersCanCreateDiscussions(),
				actual.displayCommenterFullNameSettingEnabled()
		);

		List<PklNode.Member> members = OrganizationExporter
				.settings(changed, DEFAULTS.organization());

		assertThat(members).containsExactly(
				new PklNode.Field(
						"displayName",
						PklNode.Scalar.of("Acme Inc")
				)
		);
	}

	@Test
	void aCheckOnlySettingBecomesANoteNotAField() {
		List<PklNode.Member> members = OrganizationExporter.settings(
				Actual.driftedOrganization(),
				DEFAULTS.organization()
		);

		assertThat(members).filteredOn(PklNode.Note.class::isInstance)
				.extracting(m -> ((PklNode.Note) m).text())
				.anySatisfy(
						text -> assertThat(text)
								.contains("PATCH /orgs/{org} does not accept")
				);
		assertThat(members).filteredOn(PklNode.Field.class::isInstance)
				.extracting(m -> ((PklNode.Field) m).name())
				.doesNotContain("twoFactorRequirementEnabled");
	}

}
```

If `Actual.driftedOrganization()` does not move a check-only setting off its default, extend the fixture in `src/test/java/io/github/arlol/githubcheck/testsupport/Actual.java` so it does, rather than building a third organization inline.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=OrganizationExporterTest -DskipNativeTests`
Expected: compilation failure — `OrganizationExporter` does not exist.

- [ ] **Step 3: Write the exporter**

`src/main/java/io/github/arlol/githubcheck/export/OrganizationExporter.java`. The shape, with the first three writable settings and the first check-only one written out; fill in the rest from the tables above:

```java
package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * An organization's own settings, as the config lines that differ from the
 * schema's defaults.
 * <p>
 * The ten settings in {@link #checkOnly} are reported as comments rather than
 * fields: {@code GET /orgs/{org}} returns them and {@code PATCH /orgs/{org}}
 * accepts none of them, so a field would promise a {@code --fix} that cannot
 * happen — and omitting them silently would hide a real difference from the
 * reader. The same list is in {@code OrgSettingsDriftGroup}, where the
 * comparison lives.
 */
public final class OrganizationExporter {

	private static final String NOT_WRITABLE = "drifty reports this setting but PATCH /orgs/{org} does not accept it";

	private OrganizationExporter() {
	}

	public static List<PklNode.Member> settings(
			ActualOrganization actual,
			Drifty.Organization defaults
	) {
		var members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"displayName",
								actual.displayName(),
								defaults.displayName
						),
						Fields.field(
								"description",
								actual.description(),
								defaults.description
						),
						Fields.field(
								"websiteUrl",
								actual.websiteUrl(),
								defaults.websiteUrl
						)
						// … the rest of the writable table
				)
		);
		members.addAll(checkOnly(actual, defaults));
		return List.copyOf(members);
	}

	private static List<PklNode.Member> checkOnly(
			ActualOrganization actual,
			Drifty.Organization defaults
	) {
		var notes = new ArrayList<PklNode.Member>();
		note(
				notes,
				"defaultRepositoryBranch",
				actual.defaultRepositoryBranch(),
				defaults.defaultRepositoryBranch
		);
		// … the rest of the check-only table
		return notes;
	}

	private static void note(
			List<PklNode.Member> notes,
			String name,
			Object actual,
			Object defaultValue
	) {
		if (!Objects.equals(actual, defaultValue)) {
			notes.add(
					Fields.note(
							name + " is " + actual + " on GitHub; "
									+ NOT_WRITABLE
					)
			);
		}
	}

}
```

`defaults.defaultRepositoryPermission` is a generated enum, so compare it with the `Enum<?>` overload of `Fields.field` by passing `defaults.defaultRepositoryPermission.toString()` as the default.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=OrganizationExporterTest -DskipNativeTests`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export/OrganizationExporter.java src/test/java/io/github/arlol/githubcheck/export/OrganizationExporterTest.java
git commit -m "Export an organization's own settings

The ten the PATCH will not take become comments rather than fields:
a field would promise a fix that cannot happen, and silence would
hide the difference."
```

---

### Task 5: Tolerating an unreadable group

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/FetchFailures.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/OrganizationChecker.java` (constructor, `fetchState`)
- Test: `src/test/java/io/github/arlol/githubcheck/FetchFailuresTest.java`
- Test: `src/test/java/io/github/arlol/githubcheck/OrganizationCheckerTest.java` (add one case)

**Interfaces:**
- Consumes: `io.github.arlol.githubcheck.client.GitHubApiException`.
- Produces: `FetchFailures.STRICT`, `FetchFailures.collecting()`, `failures.read(Enum<?> group, Supplier<T> read, T fallback)` returning `T`, and `failures.failures()` returning `List<FetchFailures.Failure>` where `Failure` is `record Failure(String group, String reason)`.
- `OrganizationChecker` gains a constructor taking a `FetchFailures`; the existing constructor delegates with `FetchFailures.STRICT`.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/arlol/githubcheck/FetchFailuresTest.java`:

```java
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
				() -> FetchFailures.STRICT.read(
						Drifty.OrgGroupName.ORG_TEAMS,
						() -> {
							throw new GitHubApiException("HTTP 403 reading x");
						},
						List.of()
				)
		).isInstanceOf(GitHubApiException.class);
	}

	@Test
	void collectingRecordsTheFailureAndFallsBack() {
		var failures = FetchFailures.collecting();

		List<String> value = failures.read(
				Drifty.OrgGroupName.ORG_TEAMS,
				() -> {
					throw new GitHubApiException(
							"HTTP 403 reading /orgs/acme/teams: {\"message\":\"Forbidden\"}"
					);
				},
				List.of()
		);

		assertThat(value).isEmpty();
		assertThat(failures.failures()).singleElement()
				.satisfies(failure -> {
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=FetchFailuresTest -DskipNativeTests`
Expected: compilation failure — `FetchFailures` does not exist.

- [ ] **Step 3: Write FetchFailures**

`src/main/java/io/github/arlol/githubcheck/FetchFailures.java`:

```java
package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.github.arlol.githubcheck.client.GitHubApiException;

/**
 * What a checker does when one group's read fails.
 * <p>
 * {@link #STRICT} rethrows, which is how a check or fix run behaves and has
 * always behaved: a 403 on any group ends the entry with an ERROR, because a
 * comparison against half the state would report drift that is not there.
 * <p>
 * An export wants the opposite. A token without {@code admin:org} should still
 * produce a file, with a comment where the unreadable group would have been —
 * so {@link #collecting} records the failure, hands back the empty value, and
 * the exporter turns each record into a note.
 */
public sealed interface FetchFailures {

	/**
	 * @param group  the drift group whose read failed, as the config names it
	 * @param reason the failure's first line. {@code GitHubApiException}
	 *               carries the whole response body, and a JSON blob does not
	 *               belong in a config file.
	 */
	record Failure(String group, String reason) {
	}

	<T> T read(Enum<?> group, Supplier<T> read, T fallback);

	List<Failure> failures();

	FetchFailures STRICT = new Strict();

	static FetchFailures collecting() {
		return new Collecting();
	}

	final class Strict implements FetchFailures {

		@Override
		public <T> T read(Enum<?> group, Supplier<T> read, T fallback) {
			return read.get();
		}

		@Override
		public List<Failure> failures() {
			return List.of();
		}

	}

	final class Collecting implements FetchFailures {

		private final List<Failure> failures = new ArrayList<>();

		@Override
		public <T> T read(Enum<?> group, Supplier<T> read, T fallback) {
			try {
				return read.get();
			} catch (GitHubApiException e) {
				failures.add(
						new Failure(group.toString(), firstLine(e.getMessage()))
				);
				return fallback;
			}
		}

		@Override
		public List<Failure> failures() {
			return List.copyOf(failures);
		}

		private static String firstLine(String message) {
			if (message == null) {
				return "read failed";
			}
			String line = message.lines().findFirst().orElse(message);
			int body = line.indexOf(": {");
			return body < 0 ? line : line.substring(0, body);
		}

	}

}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=FetchFailuresTest -DskipNativeTests`
Expected: PASS, 3 tests.

- [ ] **Step 5: Wire it into OrganizationChecker**

Add the field and the delegating constructor:

```java
	private final FetchFailures failures;

	OrganizationChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this(client, fix, githubSecrets, state, FetchFailures.STRICT);
	}

	OrganizationChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state,
			FetchFailures failures
	) {
		this.client = client;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
		this.failures = failures;
	}
```

Then wrap each group's read in `fetchState`. Every one currently shaped

```java
		List<ActualTeam> teams = managed.manages(Drifty.OrgGroupName.ORG_TEAMS)
				? teams(login)
				: List.of();
```

becomes

```java
		List<ActualTeam> teams = managed.manages(Drifty.OrgGroupName.ORG_TEAMS)
				? failures.read(
						Drifty.OrgGroupName.ORG_TEAMS,
						() -> teams(login),
						List.of()
				)
				: List.of();
```

Do this for all twelve groups: `ORG_ACTIONS_PERMISSIONS` (fallback `null`), `ORG_WORKFLOW_PERMISSIONS` (`null`), `ORG_ACTION_SECRETS`, `ORG_ACTION_VARIABLES`, `ORG_WEBHOOKS`, `ORG_CUSTOM_PROPERTIES`, `ORG_RULESETS`, `ORG_CODE_SECURITY_CONFIGURATIONS`, `ORG_TEAMS`, `ORG_MEMBERS`, `ORG_RUNNER_GROUPS` (all `List.of()`). Leave `client.getOrganization(login)` unwrapped: a failure there is not a group's, it is what tells drifty the organization exists.

Add the `failures()` accessor so the exporter can read them:

```java
	List<FetchFailures.Failure> fetchFailures() {
		return failures.failures();
	}
```

- [ ] **Step 6: Add a regression test that check mode is unchanged**

In `src/test/java/io/github/arlol/githubcheck/OrganizationCheckerTest.java`, following the WireMock style already there:

```java
	@Test
	void aForbiddenGroupStillFailsTheWholeEntryInCheckMode(
			WireMockRuntimeInfo wm
	) {
		stubOrganization(wm);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams"))
						.willReturn(aResponse().withStatus(403))
		);

		var entry = checker(wm).check(
				"my-org",
				Desired.organization(),
				List.of()
		);

		assertThat(entry.status()).isEqualTo(CheckResult.Status.ERROR);
	}
```

Reuse whatever stubbing helper that file already has for the organization endpoint; if it stubs inline, stub inline the same way.

- [ ] **Step 7: Run the checker tests**

Run: `./mvnw test -Dtest='FetchFailuresTest,OrganizationCheckerTest,OrganizationCheckerFixTest' -DskipNativeTests`
Expected: PASS. If an existing test now fails, the wrapping changed behaviour — `STRICT` must rethrow exactly as before.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/FetchFailures.java src/main/java/io/github/arlol/githubcheck/OrganizationChecker.java src/test/java/io/github/arlol/githubcheck/FetchFailuresTest.java src/test/java/io/github/arlol/githubcheck/OrganizationCheckerTest.java
git commit -m "Let an export survive a group it cannot read

Strict by default, so check and fix still end an entry on a 403: a
comparison against half the state reports drift that is not there.
An export collects the failure instead and comments the gap."
```

---

### Task 6: Rulesets

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/RulesetExporter.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/RulesetExporterTest.java`

**Interfaces:**
- Consumes: `Fields`, `PklNode`, `SchemaDefaults`, `io.github.arlol.githubcheck.actual.ActualRuleset`.
- Produces: `RulesetExporter.entry(ActualRuleset actual, SchemaDefaults defaults, boolean orgScope)` returning `PklNode.Member` — a `Field` keyed by the ruleset's name, whose value is an `Obj`.

Read `src/main/java/io/github/arlol/githubcheck/drift/RulesetComparison.java` first: it is the same mapping in the opposite direction, and anything it does not compare is a field the export must not invent.

Scalar fields, all on `Drifty.Ruleset` (`defaults.ruleset()`, or `defaults.orgRuleset()` when `orgScope`):

| Schema field | Actual accessor | Note |
|---|---|---|
| `target` | `target()` | |
| `enforcement` | `enforcement()` | |
| `includePatterns` | `includePatterns()` | `Fields.strings`; omit entirely when `target` is `push` or `repository` — the schema refuses them there |
| `excludePatterns` | `excludePatterns()` | same |
| `creation` | `creation()` | |
| `deletion` | `deletion()` | |
| `update` | `update()` | |
| `updateAllowsFetchAndMerge` | `updateAllowsFetchAndMerge()` | |
| `requiredSignatures` | `requiredSignatures()` | |
| `requiredLinearHistory` | `requiredLinearHistory()` | |
| `noForcePushes` | `noForcePushes()` | |
| `strictRequiredStatusChecks` | `strictRequiredStatusChecks()` | |
| `requiredDeployments` | `requiredDeployments()` | `Fields.strings` |
| `filePathRestrictions` | `filePathRestrictions()` | `Fields.strings` |
| `maxFilePathLength` | `maxFilePathLength()` | nullable `Integer` |
| `fileExtensionRestrictions` | `fileExtensionRestrictions()` | `Fields.strings` |
| `maxFileSize` | `maxFileSize()` | nullable `Integer` |

Org-scope only, from `Drifty.OrgRuleset`:

| Schema field | Actual accessor |
|---|---|
| `repositoryNameInclude` | `repositoryNameInclude()` |
| `repositoryNameExclude` | `repositoryNameExclude()` |
| `repositoryNameProtected` | `repositoryNameProtected()` |
| `repositoryPropertyInclude` | `repositoryPropertyInclude()` |
| `repositoryPropertyExclude` | `repositoryPropertyExclude()` |

Nested objects:

- `requiredStatusChecks` — a listing of `StatusCheck` objects. `context` is required; `appId` is nullable and omitted when null.
- `requiredCodeScanning` — a listing of `CodeScanningTool` objects built from `requiredCodeScanningTools()`, which is a `Set<String>` of tool names. `tool` is required; **`alertsThreshold` and `securityAlertsThreshold` are not in the actual state**, so emit only `tool` and add a note in the ruleset: `"code scanning alert thresholds are not read back; drifty compares the tool names only"`.
- `pullRequest` — present when `actual.pullRequest()` is non-null, its fields diffed against `defaults.pullRequestRule()`: `requiredApprovingReviewCount`, `dismissStaleReviewsOnPush`, `requireCodeOwnerReview`, `requireLastPushApproval`, `requiredReviewThreadResolution`, `allowedMergeMethods` (`Fields.strings`). Emit the field even when every sub-field matches its default — its *presence* is what requires pull requests — using `new PklNode.Field("pullRequest", new PklNode.Obj(members))` directly rather than `Fields.nested`.
- `mergeQueue` — same presence rule, against `defaults.mergeQueueRule()`: `checkResponseTimeoutMinutes`, `groupingStrategy`, `maxEntriesToBuild`, `maxEntriesToMerge`, `mergeMethod`, `minEntriesToMerge`, `minEntriesToMergeWaitMinutes`.
- `workflows` — a listing from `workflows()` (`Workflow(path, repositoryId, ref)`); all three required.
- `bypassActors` — a listing from `bypassActors()`; `actorId`, `actorType`, `bypassMode` all required.
- The five `RulePattern` fields — `commitMessagePattern`, `commitAuthorEmailPattern`, `committerEmailPattern`, `branchNamePattern`, `tagNamePattern` — are `String` on `ActualRuleset` and a `RulePattern` object in the schema. `ActualTypes` flattens them to the pattern text, so **the operator and negate flag are not recoverable**. Emit nothing for them and add one note when any is non-empty: `"<field> is set on GitHub; drifty compares the pattern text only, so its operator is not exported"`.

- [ ] **Step 1: Write the failing test**

`src/test/java/io/github/arlol/githubcheck/export/RulesetExporterTest.java`:

```java
package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualRuleset;

class RulesetExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static ActualRuleset ruleset(Set<String> includePatterns) {
		return new ActualRuleset(
				1L,
				"main",
				"branch",
				"active",
				includePatterns,
				Set.of(),
				false,
				false,
				false,
				false,
				false,
				true,
				false,
				false,
				Set.of(),
				null,
				Set.of(),
				Set.of(),
				"",
				"",
				"",
				"",
				"",
				null,
				Set.of(),
				Set.of(),
				null,
				Set.of(),
				null,
				List.of(),
				Set.of(),
				Set.of(),
				false,
				Set.of(),
				Set.of()
		);
	}

	@Test
	void keyedByNameWithOnlyTheDriftedRules() {
		PklNode.Member member = RulesetExporter
				.entry(ruleset(Set.of("refs/heads/main")), DEFAULTS, false);

		var field = (PklNode.Field) member;
		assertThat(field.name()).isEqualTo("main");
		assertThat(PklWriter.write(field.value())).isEqualTo("""
				includePatterns {
				  "refs/heads/main"
				}
				requiredLinearHistory = true
				""");
	}

	@Test
	void aPushRulesetHasNoRefConditions() {
		var push = new ActualRuleset(
				2L,
				"no-big-files",
				"push",
				"active",
				Set.of(),
				Set.of(),
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				null,
				Set.of(),
				Set.of(),
				"",
				"",
				"",
				"",
				"",
				null,
				Set.of(),
				Set.of(),
				null,
				Set.of(),
				100,
				List.of(),
				Set.of(),
				Set.of(),
				false,
				Set.of(),
				Set.of()
		);

		var field = (PklNode.Field) RulesetExporter.entry(push, DEFAULTS, false);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				target = "push"
				maxFileSize = 100
				""");
	}

	@Test
	void aPullRequestRuleIsEmittedEvenWhenAllItsFieldsAreDefault() {
		var withPr = ruleset(Set.of("refs/heads/main"));
		var pr = new ActualRuleset.PullRequest(
				0,
				false,
				false,
				false,
				false,
				Set.of()
		);

		var field = (PklNode.Field) RulesetExporter.entry(
				withPullRequest(withPr, pr),
				DEFAULTS,
				false
		);

		assertThat(PklWriter.write(field.value())).contains("pullRequest {");
	}

	private static ActualRuleset withPullRequest(
			ActualRuleset base,
			ActualRuleset.PullRequest pullRequest
	) {
		return new ActualRuleset(
				base.id(),
				base.name(),
				base.target(),
				base.enforcement(),
				base.includePatterns(),
				base.excludePatterns(),
				base.creation(),
				base.deletion(),
				base.update(),
				base.updateAllowsFetchAndMerge(),
				base.requiredSignatures(),
				base.requiredLinearHistory(),
				base.noForcePushes(),
				base.strictRequiredStatusChecks(),
				base.requiredStatusChecks(),
				pullRequest,
				base.requiredCodeScanningTools(),
				base.requiredDeployments(),
				base.commitMessagePattern(),
				base.commitAuthorEmailPattern(),
				base.committerEmailPattern(),
				base.branchNamePattern(),
				base.tagNamePattern(),
				base.mergeQueue(),
				base.workflows(),
				base.filePathRestrictions(),
				base.maxFilePathLength(),
				base.fileExtensionRestrictions(),
				base.maxFileSize(),
				base.bypassActors(),
				base.repositoryNameInclude(),
				base.repositoryNameExclude(),
				base.repositoryNameProtected(),
				base.repositoryPropertyInclude(),
				base.repositoryPropertyExclude()
		);
	}

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RulesetExporterTest -DskipNativeTests`
Expected: compilation failure — `RulesetExporter` does not exist.

- [ ] **Step 3: Write the exporter**

Follow `OrganizationExporter`'s shape: a public static `entry` that builds `List<PklNode.Member>` with `Fields.members(...)`, plus private static helpers `statusChecks`, `codeScanning`, `pullRequest`, `mergeQueue`, `workflows`, `bypassActors`, `propertyConditions`, and `patternNotes`. Return `new PklNode.Field(actual.name(), new PklNode.Obj(members))`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=RulesetExporterTest -DskipNativeTests`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export/RulesetExporter.java src/test/java/io/github/arlol/githubcheck/export/RulesetExporterTest.java
git commit -m "Export a ruleset

A pull_request or merge_queue rule is emitted even when every field
is default: its presence is the setting. Rule patterns and code
scanning thresholds are not read back, so they get a note instead of
an invented value."
```

---

### Task 7: The organization's keyed collections

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/TeamExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/WebhookExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/CustomPropertyExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/CodeSecurityConfigurationExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/RunnerGroupExporter.java`
- Test: one test class per exporter, same package, same shape as `RulesetExporterTest`

**Interfaces:**
- Consumes: `Fields`, `PklNode`, `SchemaDefaults`, and the matching `Actual*` record.
- Produces, each returning `PklNode.Member` keyed by the entity's config key:
  - `TeamExporter.entry(ActualTeam, Drifty.Team)` — keyed by `slug()`
  - `WebhookExporter.entry(ActualWebhook, Drifty.Webhook)` — keyed by the hook's url host+path, since GitHub has no name for one
  - `CustomPropertyExporter.entry(ActualCustomProperty, Drifty.CustomProperty)` — keyed by `name()`
  - `CodeSecurityConfigurationExporter.entry(ActualCodeSecurityConfiguration, Drifty.CodeSecurityConfiguration)` — keyed by `name()`
  - `RunnerGroupExporter.entry(ActualRunnerGroup, Drifty.RunnerGroup)` — keyed by `name()`

Mappings:

**Team** (`ActualTeam` → `Drifty.Team`): `name` (emit only when `actual.name()` differs from the slug — the schema defaults it to the slug), `description`, `privacy`, `notificationSetting`, `parent` (nullable), `members` (`Fields.strings`), `maintainers` (`Fields.strings`).

**Webhook** (`ActualWebhook` → `Drifty.Webhook`): `url` (required), `contentType`, `insecureSsl`, `active`, `events` (`Fields.strings`), `secret` from `hasSecret()`. When `hasSecret()`, add the note `"the webhook secret is never returned by GitHub; supply it through DRIFTY_GITHUB_SECRETS"`.

**CustomProperty** (`ActualCustomProperty` → `Drifty.CustomProperty`): `valueType` (required), `required`, `defaultValue` (nullable), `defaultValues` (`Fields.strings`), `description` (nullable), `allowedValues` (`Fields.strings`), `valuesEditableBy`.

**CodeSecurityConfiguration** (`ActualCodeSecurityConfiguration` → `Drifty.CodeSecurityConfiguration`): `description`, `enforcement`, `defaultForNewRepos`, `repositories` (`Fields.strings`), and the seventeen security settings which live in `actual.settings()`, a `Map<String, String>` keyed by wire name. Take the schema-field-to-wire-name pairs from the `Setting` table in `OrgCodeSecurityConfigurationsDriftGroup` — do not retype them from memory. `codeScanningDefaultSetupOptions` is emitted only when `actual.codeScanningRunnerType()` is non-null, as a nested object with `runnerType` and `runnerLabel`; `secretScanningDelegatedBypassOptions` only when `secretScanningDelegatedBypassReviewers()` is non-empty. Add the note `"setting these options means drifty compares them from now on; GitHub picks a runner type itself when default setup is enabled"` beside either.

**RunnerGroup** (`ActualRunnerGroup` → `Drifty.RunnerGroup`): read `config/drifty.pkl`'s `RunnerGroup` class for the field list; map `visibility`, `allowsPublicRepositories`, `restrictedToWorkflows`, `selectedWorkflows` (`Fields.strings`), `selectedRepositories` (`Fields.strings`). Skip a group whose `isDefault()` is true only if the schema cannot represent it — check what `OrgRunnerGroupsDriftGroup` does with the default group and match it.

Each test asserts two things: an entity at GitHub's defaults exports an entry with no members, and one changed field exports exactly that field. Build the `Actual*` fixtures inline the way `RulesetExporterTest` does.

- [ ] **Step 1: Write the five failing tests**

One class per exporter, each with the two cases above. Follow `RulesetExporterTest` exactly for structure — `SchemaDefaults.of(config/drifty.pkl)` as a static field, `PklWriter.write(field.value())` for the assertion.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='TeamExporterTest,WebhookExporterTest,CustomPropertyExporterTest,CodeSecurityConfigurationExporterTest,RunnerGroupExporterTest' -DskipNativeTests`
Expected: compilation failure — none of the exporters exist.

- [ ] **Step 3: Write the five exporters**

Each follows `RulesetExporter`: a public static `entry` returning `new PklNode.Field(key, new PklNode.Obj(Fields.members(...)))`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw test -Dtest='TeamExporterTest,WebhookExporterTest,CustomPropertyExporterTest,CodeSecurityConfigurationExporterTest,RunnerGroupExporterTest' -DskipNativeTests`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export src/test/java/io/github/arlol/githubcheck/export
git commit -m "Export an organization's teams, hooks, properties, configs and runner groups

A webhook is keyed by its url because GitHub has no name for one, and
the code security settings map is read through the same table the
drift group compares it with."
```

---

### Task 8: Assembling an organization entry

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/AccountExporter.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/AccountExporterTest.java`

**Interfaces:**
- Consumes: every exporter so far, `OrganizationState`, `FetchFailures.Failure`.
- Produces: `AccountExporter.organization(OrganizationState state, List<FetchFailures.Failure> failures, List<PklNode> repositories, SchemaDefaults defaults)` returning `PklNode.Member` keyed by login, and `AccountExporter.user(String login, List<PklNode> repositories)` returning the same for a personal account.

The entry's members, in this order: the organization's own settings (Task 4), `actionsPermissions` and the two workflow-permission fields, then `actionsSecrets`, `actionsVariables`, `webhooks`, `customProperties`, `rulesets`, `codeSecurityConfigurations`, `teams`, `members`, `runnerGroups`, then `repositories`. A failure recorded for a group becomes a note in that group's position, using the group name as the config spells it — `Fields.note(failure.group() + ": " + failure.reason())`.

`actionsSecrets` is a `Mapping<String, OrgSecret>`; emit `visibility` and `selectedRepositories` per secret and one note per mapping: `"secret values are never returned by GitHub; supply them through DRIFTY_GITHUB_SECRETS"`. `members` is a `Mapping<String, OrgRole>` — a scalar value per key, so build `new PklNode.Field(login, PklNode.Scalar.of(role))`.

- [ ] **Step 1: Write the failing test**

Assert three things against a hand-built `OrganizationState`: an untouched organization exports `["acme"] {\n}`; a recorded failure for `org_teams` puts a note where `teams` would be; and a state with one team and one ruleset nests both under the login.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=AccountExporterTest -DskipNativeTests`
Expected: compilation failure — `AccountExporter` does not exist.

- [ ] **Step 3: Write the assembler**

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw test -Dtest=AccountExporterTest -DskipNativeTests`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export/AccountExporter.java src/test/java/io/github/arlol/githubcheck/export/AccountExporterTest.java
git commit -m "Assemble one account entry

A group the token could not read becomes a note in that group's
position, so an absent section is never mistaken for an empty one."
```

---

### Task 9: Repository scalar settings

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/RepositoryExporter.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/RepositoryExporterTest.java`

**Interfaces:**
- Consumes: `Fields`, `PklNode`, `SchemaDefaults`, `RepositoryState`.
- Produces: `RepositoryExporter.entry(RepositoryState state, SchemaDefaults defaults)` returning `PklNode` — an `Obj` for a `new { … }` element of the `repositories` listing.

`name` is required and comes from `state.ref().name()`, not from `ActualRepository`.

From `ActualRepository`:

| Schema field | Actual accessor | Note |
|---|---|---|
| `archived` | `archived()` | |
| `description` | `description()` | |
| `homepageUrl` | `homepage()` | the one name that differs |
| `visibility` | `visibility()` | enum; **and** a note, since SPEC.md says drifty reports but never writes it: `"visibility is <v> on GitHub; drifty reports it and never changes it"` |
| `defaultBranch` | `defaultBranch()` | |
| `topics` | `topics()` | `Fields.strings` |
| `hasIssues` | `hasIssues()` | |
| `hasProjects` | `hasProjects()` | |
| `hasWiki` | `hasWiki()` | |
| `hasDiscussions` | `hasDiscussions()` | |
| `isTemplate` | `isTemplate()` | |
| `allowForking` | `allowForking()` | |
| `webCommitSignoffRequired` | `webCommitSignoffRequired()` | |
| `allowMergeCommit` | `allowMergeCommit()` | |
| `allowSquashMerge` | `allowSquashMerge()` | |
| `allowRebaseMerge` | `allowRebaseMerge()` | |
| `allowAutoMerge` | `allowAutoMerge()` | |
| `allowUpdateBranch` | `allowUpdateBranch()` | |
| `deleteBranchOnMerge` | `deleteBranchOnMerge()` | |
| `squashMergeCommitTitle` | `squashMergeCommitTitle()` | enum |
| `squashMergeCommitMessage` | `squashMergeCommitMessage()` | enum |
| `mergeCommitTitle` | `mergeCommitTitle()` | enum |
| `mergeCommitMessage` | `mergeCommitMessage()` | enum |

From the state's own fields and `ActualSecurityAndAnalysis`:

| Schema field | Source |
|---|---|
| `vulnerabilityAlerts` | `state.vulnerabilityAlerts()` |
| `automatedSecurityFixes` | `state.automatedSecurityFixes()` |
| `immutableReleases` | `state.immutableReleases()` |
| `privateVulnerabilityReporting` | `state.privateVulnerabilityReporting()` |
| `codeScanningDefaultSetup` | `state.codeScanningDefaultSetup()` |
| `secretScanning` | `securityAndAnalysis().secretScanning()` |
| `secretScanningPushProtection` | `securityAndAnalysis().secretScanningPushProtection()` |
| `secretScanningValidityChecks` | `securityAndAnalysis().secretScanningValidityChecks()` |
| `secretScanningNonProviderPatterns` | `securityAndAnalysis().secretScanningNonProviderPatterns()` |
| `advancedSecurity` | `securityAndAnalysis().advancedSecurity()` |
| `secretScanningAiDetection` | `securityAndAnalysis().secretScanningAiDetection()` |
| `secretScanningDelegatedAlertDismissal` | `securityAndAnalysis().secretScanningDelegatedAlertDismissal()` |
| `secretScanningDelegatedBypass` | `securityAndAnalysis().secretScanningDelegatedBypass()` |
| `secretScanningDelegatedBypassReviewers` | `securityAndAnalysis().bypassReviewers()`, a listing of objects with required `reviewerId` and `reviewerType` |
| `defaultWorkflowPermissions` | `state.workflowPermissions().defaultWorkflowPermissions()` |
| `canApprovePullRequestReviews` | `state.workflowPermissions().canApprovePullRequestReviews()` |

**An archived repository emits `archived = true`, its non-security settings, and the note** `"security settings are not read for an archived repository"` — the checker does not fetch them, so the false they read is absence, not a value. Guard the whole security block on `!actual.archived()`.

`state.workflowPermissions()` is null when the group is unmanaged, which an export never does — but guard for null anyway rather than dereferencing.

- [ ] **Step 1: Write the failing test**

Three cases: a repository at GitHub's defaults exports only `name`; a changed merge setting exports `name` and that setting; an archived repository exports `name`, `archived = true` and the archived note, with no security fields.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=RepositoryExporterTest -DskipNativeTests`
Expected: compilation failure — `RepositoryExporter` does not exist.

- [ ] **Step 3: Write the exporter**

- [ ] **Step 4: Run it to verify it passes**

Run: `./mvnw test -Dtest=RepositoryExporterTest -DskipNativeTests`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export/RepositoryExporter.java src/test/java/io/github/arlol/githubcheck/export/RepositoryExporterTest.java
git commit -m "Export a repository's own settings

An archived repository's security flags read false because they are
never fetched, so they are commented rather than written as values."
```

---

### Task 10: The repository's keyed collections

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/BranchProtectionExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/EnvironmentExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/export/PagesExporter.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/export/RepositoryExporter.java` (attach the collections)
- Test: one class per new exporter; extend `RepositoryExporterTest`

**Interfaces:**
- Produces: `BranchProtectionExporter.entry(String pattern, ActualBranchProtection, Drifty.BranchProtection)`, `EnvironmentExporter.entry(String name, ActualEnvironment, List<ActualSecret> secrets, List<ActualVariable> variables, Drifty.Environment)`, `PagesExporter.node(ActualPages, Drifty.Pages)` returning `PklNode`.

**BranchProtection** — the schema is flat, `ActualBranchProtection` is not. Direct fields: `enforceAdmins`, `requiredLinearHistory`, `allowForcePushes`, `allowDeletions`, `blockCreations`, `lockBranch`, `allowForkSyncing`, `requireConversationResolution`, `strictStatusChecks`, `requiredStatusChecks` (listing of `StatusCheck`). From `pullRequestReviews()`, present only when the `Optional` is: `requiredApprovingReviewCount` (nullable), `dismissStaleReviews`, `requireCodeOwnerReviews`, `requireLastPushApproval` (nullable `Boolean`), and from its two `Actors`: `dismissalUsers`/`dismissalTeams`/`dismissalApps` and `bypassPullRequestUsers`/`bypassPullRequestTeams`/`bypassPullRequestApps`. From `restrictions()`, present only when the `Optional` is: `users`, `teams`, `apps`.

**Environment** — `waitTimer`, `preventSelfReview`, `protectedBranches`, `customBranchPolicies` map straight across. Two need unpicking:
- `reviewers()` is a `Set<String>` of keys built by `ActualTypes.reviewerKey(type, name)`. Read that method and split the set into `reviewerUsers` and `reviewerTeams` by its prefix — do not invent the prefix.
- `branchPolicies()` is a `List<BranchPolicy>` with a `type`; the ones typed as branches become `deploymentBranchPatterns`, the ones typed as tags `deploymentTagPatterns`. Read `client.BranchPolicyType` for the two constants.
- `secrets` become a `Listing<String>` of names plus the secret-value note; `variables` become a `Mapping<String, String>` of name to value.

**Pages** — `buildType` and, when `source()` is present, `sourceBranch` and `sourcePath`. `ActualPages.httpsEnforced()` has no schema field; do not emit it.

Then in `RepositoryExporter.entry`, append: `branchProtections` (mapping, keyed by branch pattern), `rulesets` (mapping via `RulesetExporter.entry(…, false)`), `environments` (mapping), `webhooks` (mapping via `WebhookExporter`), `actionsSecrets` (listing of names + note), `actionsVariables` (mapping of name to value), `pages` (object or nothing), `customProperties` (mapping from `ActualCustomPropertyValue`), `collaborators` and `teamPermissions` (from `ActualCollaborators.users()` and `teams()`, each a mapping of name to permission). Check `config/drifty.pkl`'s `Repository` class for the exact field names of the last four before writing them.

- [ ] **Step 1: Write the failing tests**
- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='BranchProtectionExporterTest,EnvironmentExporterTest,PagesExporterTest,RepositoryExporterTest' -DskipNativeTests`

- [ ] **Step 3: Write the three exporters and attach the collections**
- [ ] **Step 4: Run them to verify they pass**

Run: `./mvnw test -Dtest='BranchProtectionExporterTest,EnvironmentExporterTest,PagesExporterTest,RepositoryExporterTest' -DskipNativeTests`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/export src/test/java/io/github/arlol/githubcheck/export
git commit -m "Export a repository's protections, environments and pages

Branch protection is flat in the schema and nested on the wire, and an
environment's reviewers arrive as prefixed keys, so both are unpicked
here rather than in the writer."
```

---

### Task 11: Tolerating an unreadable group per repository

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/RepositoryChecker.java` (constructor, `fetchState`)
- Test: `src/test/java/io/github/arlol/githubcheck/RepositoryCheckerFetchStateTest.java` (add one case)

Same change as Task 5, on the other checker. Add the `FetchFailures` field and a delegating constructor, then wrap each group's read: `BRANCH_PROTECTION`, `ACTION_SECRETS`, `ACTION_VARIABLES`, the environment block (`ENVIRONMENT_CONFIG`/`ENVIRONMENT_SECRETS`/`ENVIRONMENT_VARIABLES` — wrap the whole `if` body under `ENVIRONMENT_CONFIG`, since one listing serves all three), `RULESETS`, `WEBHOOKS`, `PAGES`, `CUSTOM_PROPERTIES`, `COLLABORATORS`, `WORKFLOW_PERMISSIONS`, and the security-flag block in `fetchSecurityFlags`. Leave `client.getRepo(org, name)` unwrapped.

`fetchState` declares `throws IOException, InterruptedException`; `FetchFailures.read` takes a `Supplier`, which cannot throw those. Where a group's read is inside the checked-exception path, keep the existing try/catch structure and wrap only the `GitHubApiException`-throwing call.

- [ ] **Step 1: Write the failing test** — a repository whose branch-protection read 403s still reports ERROR in check mode, mirroring Task 5's regression test.
- [ ] **Step 2: Run it to verify it fails** — it will pass already if the wrapping is not yet done; that is the point, it is a regression guard. Write it, watch it pass, then make the change and watch it still pass.
- [ ] **Step 3: Make the change**
- [ ] **Step 4: Run the repository checker tests**

Run: `./mvnw test -Dtest='RepositoryChecker*' -DskipNativeTests`
Expected: PASS. Any failure means `STRICT` is not rethrowing where it used to.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/RepositoryChecker.java src/test/java/io/github/arlol/githubcheck/RepositoryCheckerFetchStateTest.java
git commit -m "Collect a repository group's read failure in export mode

The same seam as the organization checker, strict by default."
```

---

### Task 12: The file, the runner and the command line

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/export/DriftyFileExporter.java`
- Create: `src/main/java/io/github/arlol/githubcheck/ExportRunner.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java`
- Test: `src/test/java/io/github/arlol/githubcheck/export/DriftyFileExporterTest.java`
- Test: `src/test/java/io/github/arlol/githubcheck/GitHubCheckTest.java` (argument parsing)

**Interfaces:**
- Produces:
  - `DriftyFileExporter.file(String schemaUri, String version, Instant now, List<PklNode.Member> organizations, List<PklNode.Member> users)` returning `String`
  - `ExportRunner.run(GitHubClient client, List<String> logins, Path out, String schemaUri)` returning `int` (the exit code)
  - `GitHubCheck.exportLogins(List<String> args)` returning `List<String>` — the arguments after `--export` up to the next `--`-prefixed one

- [ ] **Step 1: Write the failing tests**

`DriftyFileExporterTest` asserts the header, the `amends` line and both blocks, and that an empty `users` list omits the `users` block entirely.

In `GitHubCheckTest`:

```java
	@Test
	void exportLogins_takesEveryArgumentUntilTheNextOption() {
		assertThat(
				GitHubCheck.exportLogins(
						List.of("--export", "acme", "arlol", "--out", "x.pkl")
				)
		).containsExactly("acme", "arlol");
	}

	@Test
	void exportLogins_emptyWhenExportIsAbsent() {
		assertThat(GitHubCheck.exportLogins(List.of("--fix"))).isEmpty();
	}

	@Test
	void exportLogins_emptyWhenExportNamesNoLogin() {
		assertThat(
				GitHubCheck.exportLogins(List.of("--export", "--out", "x.pkl"))
		).isEmpty();
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='DriftyFileExporterTest,GitHubCheckTest' -DskipNativeTests`

- [ ] **Step 3: Write the file exporter**

The header, exactly:

```
/// Exported by drifty <version> from <logins, comma-separated> on <ISO-8601 instant>.
/// Only settings that differ from the schema defaults are listed; everything
/// absent is at GitHub's default.
amends "<schemaUri>"
```

Then a blank line, then `organizations { … }` and, when non-empty, `users { … }`.

- [ ] **Step 4: Write the runner**

`ExportRunner.run` for each login: `client.getOrganization(login)`; when present, build an `OrganizationChecker` with `FetchFailures.collecting()`, call `fetchState(login, ManagedGroups.all(Drifty.OrgGroupName.class))`, list the repositories, fetch each one's state through a `RepositoryChecker` with its own collecting failures, and hand the lot to `AccountExporter.organization`. When absent, compare the login against `client.getAuthenticatedUser().login()`; on a mismatch print

```
ERROR: <login> is not an organization, and a personal account can only be exported by its own token
```

and count the login as failed. Otherwise list `client.listUserRepos(login)` and build a `users` entry. Print progress on stdout the way `GitHubCheck.check` does (`Fetching repo list for organization: …`, `Found N repos…`). Write the file with `Files.writeString(out, text)` and print `Wrote <path> (<n> lines)`. Return 1 if any login failed, 0 otherwise.

- [ ] **Step 5: Wire the flag into main**

In `GitHubCheck.main`, after the `--self-test` branch and the token check:

```java
		List<String> exportLogins = exportLogins(argsList);
		if (!exportLogins.isEmpty()) {
			if (fix || configArg != null) {
				System.err.println(
						"ERROR: --export takes no --config and no --fix"
				);
				System.exit(1);
				return;
			}
			String schema = optionValue(argsList, "--schema");
			System.exit(
					ExportRunner.run(
							new GitHubClient(token),
							exportLogins,
							Path.of(
									optionValue(argsList, "--out") == null
											? "export.pkl"
											: optionValue(argsList, "--out")
							),
							schema == null ? SchemaDefaults.MAIN_SCHEMA_URI
									: schema
					)
			);
			return;
		}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest='DriftyFileExporterTest,GitHubCheckTest' -DskipNativeTests`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck src/test/java/io/github/arlol/githubcheck
git commit -m "Add --export

Resolves each login to an organization or the token's own personal
account: /user/repos serves the authenticated user alone, so
exporting somebody else's account has to fail rather than emit the
token owner's repositories under their name."
```

---

### Task 13: The round-trip acceptance test

**Files:**
- Test: `src/test/java/io/github/arlol/githubcheck/export/ExportRoundTripTest.java`

**Interfaces:**
- Consumes: everything.

This is the test the design rests on: an exported file, loaded back, must report no drift against the state it was exported from.

- [ ] **Step 1: Write the test**

```java
package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

@WireMockTest
class ExportRoundTripTest {

	@Test
	void anExportedFileReportsNoDriftAgainstTheStateItCameFrom(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubAnOrganizationWithDriftedSettings(wm);
		Path out = dir.resolve("export.pkl");

		int code = ExportRunner.run(
				client(wm),
				java.util.List.of("my-org"),
				out,
				Path.of("config/drifty.pkl").toAbsolutePath().toString()
			);

		assertThat(code).isZero();

		var config = io.github.arlol.githubcheck.PklConfigLoader.load(out);
		var result = io.github.arlol.githubcheck.GitHubCheck.check(
				config,
				client(wm),
				orgChecker(wm),
				repoChecker(wm)
		);

		assertThat(result.hasDrift()).isFalse();
	}

}
```

Fill in `stubAnOrganizationWithDriftedSettings`, `client`, `orgChecker` and `repoChecker` from the stubbing already in `OrganizationCheckerTest` and `RepositoryCheckerCheckTest`. The organization must have at least: a non-default `description`, one team, one ruleset with a `pullRequest` rule, one webhook, and one repository with a non-default merge setting and one environment. The exported file `amends` the local schema path, which is what `--schema` is for.

- [ ] **Step 2: Run it**

Run: `./mvnw test -Dtest=ExportRoundTripTest -DskipNativeTests`
Expected: PASS. A failure here names the setting whose export and comparison disagree — that is the test doing its job; fix the exporter, not the assertion.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/github/arlol/githubcheck/export/ExportRoundTripTest.java
git commit -m "Round-trip the export through the checker

An exported file that reports drift against the state it came from is
a file that would tell a --fix run to change something back."
```

---

### Task 14: The schema coverage guard

**Files:**
- Test: `src/test/java/io/github/arlol/githubcheck/export/SchemaCoverageTest.java`

**Interfaces:**
- Consumes: `Drifty.Organization`, `Drifty.Repository`, `Drifty.Ruleset`, the exporters.

Reflection here is test-scope only, so it needs no production native-image metadata — the same reason `ReachabilityMetadata` lives in `src/test`.

- [ ] **Step 1: Write the test**

```java
package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Every schema field has an exporter line.
 * <p>
 * A field added to {@code config/drifty.pkl} that no exporter emits is absent
 * from every exported file and nothing else notices: the round-trip test
 * passes, because a field the export omits is a field the config leaves at its
 * default and the comparison agrees. This is what fails instead — the export's
 * counterpart to {@code DriftPathNamespacingTest}.
 */
class SchemaCoverageTest {

	private static final Set<String> NOT_EXPORTED = Set.of(
			// an export manages every group, so it never writes one
			"managed",
			// nested collections, covered by their own exporters
			"repositories"
	);

	@Test
	void everyOrganizationFieldIsEmittedForAnAllDriftedOrganization() {
		String exported = PklWriter.write(
				new PklNode.Obj(
						AccountExporter.organizationMembers(
								allDriftedOrganizationState(),
								List.of(),
								List.of(),
								defaults()
						)
				)
		);

		assertThat(fieldNames(Drifty.Organization.class))
				.filteredOn(name -> !NOT_EXPORTED.contains(name))
				.allSatisfy(name -> assertThat(exported).contains(name));
	}

	private static List<String> fieldNames(Class<?> type) {
		return java.util.Arrays.stream(type.getFields())
				.map(Field::getName)
				.toList();
	}

}
```

Write `allDriftedOrganizationState()` as a fixture in which **every** exportable field is off its default, and the same for `Drifty.Repository` and `Drifty.Ruleset` in two more test methods. Where a field genuinely cannot be exported — the rule patterns and code scanning thresholds from Task 6 — add it to `NOT_EXPORTED` **with a comment saying why**, so the exemption is a decision rather than an oversight.

If `AccountExporter.organizationMembers` does not exist as a package-private seam, add it in Task 8's class and have `organization()` call it.

- [ ] **Step 2: Run it**

Run: `./mvnw test -Dtest=SchemaCoverageTest -DskipNativeTests`
Expected: PASS once every field has a line. Each failure names a field with no exporter — add the line.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/github/arlol/githubcheck/export/SchemaCoverageTest.java
git commit -m "Fail the build when a schema field has no exporter line

Nothing else catches it: a field the export omits is one the config
leaves at its default, and the round-trip agrees with itself."
```

---

### Task 15: The shipped image and the docs

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java` (`selfTest`)
- Modify: `src/test/java/io/github/arlol/githubcheck/NativeExecutableIT.java`
- Modify: `SPEC.md`, `README.md`, `FEATURES.md`, `CLAUDE.md`

- [ ] **Step 1: Extend the self-test**

`--self-test` is the only thing covering the production binary's reflective paths. Add a third check to `selfTest`: build a small `OrganizationState` in code, render it with `AccountExporter` and `PklWriter`, and fail when the output does not contain the expected line. Do not evaluate the schema there — that path is already covered by the config branch, and a self-test must not need the network.

```java
		String exported = PklWriter.write(
				new PklNode.Obj(
						List.of(
								new PklNode.Field(
										"displayName",
										PklNode.Scalar.of("drifty-self-test")
								)
						)
				)
		);
		if (!exported.contains("displayName = \"drifty-self-test\"")) {
			System.err.println("self-test FAILED: export render");
			return 1;
		}
```

- [ ] **Step 2: Run the native image checks**

Run: `./mvnw -DskipTests package` then `./target/drifty --self-test`
Expected: `self-test OK`.

Run: `./mvnw clean verify`
Expected: PASS, including the native test image.

- [ ] **Step 3: Document it**

- `README.md` — a short section showing `drifty --export acme` and what lands in `export.pkl`.
- `SPEC.md` — a section beside the CLI documentation: the flags, the default output path, the amends URI, the non-defaults-only rule, the four kinds of note, and what does not round-trip.
- `FEATURES.md` — the export as an implemented feature.
- `CLAUDE.md` — add, in the "Adding or changing a managed setting" list, the two rules a future change must not break:
  - *A new schema field needs an exporter line.* `SchemaCoverageTest` fails the build otherwise, and nothing else would: a field the export omits is one the config leaves at its default, so the round-trip test agrees with itself.
  - *A setting drifty reports but cannot write is exported as a note, never a field.* `visibility` and the ten check-only org settings; a field there promises a `--fix` that cannot happen.

- [ ] **Step 4: Commit and push**

```bash
git add -A
git commit -m "Cover the export in the shipped image and document it"
git push -u origin claude/drifty-pkl-export-settings-lisxqe
```

---

## Self-Review

**Spec coverage.** Every section of the design maps to a task: the example file and non-defaults rule → Tasks 2, 4, 9; the command line → Task 12; state and `FetchFailures` → Tasks 5, 11; the node tree and writer → Task 1; defaults from the schema → Task 3; the four kinds of note → Tasks 4 (check-only), 5+8 (unreadable group), 7 (secrets), 6+9 (not recoverable, archived); what does not round-trip → Tasks 6, 7, 9; testing → Tasks 13, 14, 15.

**Known gap, deliberate.** The design lists `SecretExporter` and `VariableExporter` as their own files; secrets and variables turned out to be a mapping of two or three fields each, so they are built inline in `AccountExporter` (Task 8) and `RepositoryExporter` (Task 10) instead. No behaviour is lost.

**Type consistency.** `PklNode.Field`/`Note` are the two `Member` kinds throughout; every section exporter returns `PklNode.Member` for a keyed entry and `PklNode` for a listing element; `Fields.*` returns `Optional<PklNode.Member>` everywhere except `note` and `members`. `SchemaDefaults` accessor names match the `export-defaults.pkl` property names one for one.
