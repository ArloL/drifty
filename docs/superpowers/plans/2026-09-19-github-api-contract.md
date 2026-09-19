# GitHub API Contract Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fail the build when a `client` request or response record declares a
field name, enum value or type that GitHub's own OpenAPI spec does not carry
for the endpoint it belongs to — and, on request bodies, when the spec carries
a settable field the record does not.

**Architecture:** A `@GitHubEndpoint` annotation on each wire record names the
endpoints and direction it serves. `download-schemas.py --contract` reduces
GitHub's dereferenced spec to a 483 KB committed file holding, per endpoint and
direction, the property tree, types, enum values and nullability. A JUnit test
walks each annotated record through the very same `ObjectMapper` configuration
`GitHubClient` builds — so wire names come from Jackson rather than from a
second guess — and compares the two.

**Tech Stack:** Java 25 records, Jackson 2 introspection
(`BeanDescription.findProperties`), ClassGraph 4.8 (test scope), JUnit Jupiter
`@TestFactory`, AssertJ, Python 3 for the extractor.

**Spec:** `docs/superpowers/specs/2026-09-19-github-api-contract-design.md`

## Global Constraints

- The check must not need the network. Only the committed contract file is read.
- Wire names and enum values are obtained by asking Jackson, never by
  reimplementing `PropertyNamingStrategies.SNAKE_CASE`.
- `@GitHubEndpoint` is `RetentionPolicy.CLASS`. It is never read at runtime, so
  it must stay out of `reachability-metadata.json`.
- The metadata ignore rule matches at the **root** of a response object only —
  a webhook's `config.url` is a managed setting at depth 1.
- Stage 1 enforces the reverse direction on request bodies only. Response
  bodies are forward-only, behind a named list that FOLLOWUPS.md tracks.
- Existing formatting rules apply: tabs, `.settings/code-formatter-profile.xml`
  is applied automatically by `./mvnw verify`.
- Iterate with `./mvnw test -DskipNativeTests`; run `./mvnw verify` once before
  pushing.

---

### Task 1: The `@GitHubEndpoint` annotation

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/client/GitHubEndpoint.java`

**Interfaces:**
- Produces: `@GitHubEndpoint(String[] request, String[] response, String[] unmanaged, String[] undocumented)`

- [ ] **Step 1: Write the annotation**

```java
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GitHubEndpoint {
	String[] request() default {};
	String[] response() default {};
	String[] unmanaged() default {};
	String[] undocumented() default {};
}
```

An endpoint string is `"<METHOD> <path>"` with the path exactly as OpenAPI
spells it, including braces: `"PATCH /repos/{owner}/{repo}"`.

An `unmanaged` or `undocumented` entry is a path, then ` — `, then prose:
`"billing_email — drifty does not manage billing"`. The path is dotted for a
nested property and carries `#` for a single enum value (`target#actions`).

- [ ] **Step 2: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/client/GitHubEndpoint.java
git commit -m "Add the annotation that names a record's endpoint"
```

---

### Task 2: `--contract` mode in the extractor

**Files:**
- Modify: `download-schemas.py`
- Create: `src/test/resources/github-api-contract.json`

**Interfaces:**
- Produces: the contract file, shaped

```json
{
 "apiVersion": "2026-03-10",
 "specCommit": "<sha of github/rest-api-description main>",
 "endpoints": {
  "PATCH /orgs/{org}": {
   "request":  {"type": "object", "properties": {...}, "required": [...]},
   "response": {"type": "object", "properties": {...}}
  }
 }
}
```

A node is `{"type", "enum", "nullable", "properties", "required", "items",
"oneOf"}`, every key optional. `oneOf` appears only for a **discriminated**
union and is a map from discriminator value to node.

- [ ] **Step 1: Find the endpoints to extract**

The script scans `src/main/java/io/github/arlol/githubcheck/client/*.java` for
string literals matching `^(GET|POST|PUT|PATCH|DELETE) /`. Reading Java
annotations from Python is not worth a build coupling, and the loop still
closes: Task 5's test fails if an annotated endpoint is missing from the
contract file, so a typo or a skipped refresh is caught where it matters.

- [ ] **Step 2: Reduce each operation**

Keep only `type`, `enum` (strings, sorted), `nullable`, `properties`,
`required` (sorted), `items`. Drop everything else. Response: the lowest 2xx
with an `application/json` schema.

`allOf` and `anyOf` merge their branches' properties into one set, because
Jackson flattens them into one record. A `oneOf` whose branches each pin one
property to a single-value `enum` or `const` is **discriminated**: keep it as a
map keyed by that value. Any other `oneOf` merges like `allOf`.

- [ ] **Step 3: Write it sorted**

`json.dumps(..., indent=1, sort_keys=True)` so a refresh diffs line by line.

- [ ] **Step 4: Run it and check the size**

Run: `python3 download-schemas.py --contract`
Expected: a file under 1 MB naming ~60 endpoints, and no "not found in spec".

- [ ] **Step 5: Commit**

---

### Task 3: `ApiContract` — reading the contract file

**Files:**
- Create: `src/test/java/io/github/arlol/githubcheck/client/ApiContract.java`
- Create: `src/test/java/io/github/arlol/githubcheck/client/ApiContractTest.java`
- Create: `src/test/resources/contract-fixture.json`

**Interfaces:**
- Produces:
  - `record SchemaNode(String type, Set<String> enumValues, boolean nullable, Map<String, SchemaNode> properties, Set<String> required, SchemaNode items, Map<String, SchemaNode> oneOf)`
  - `static ApiContract load(Path)` / `static ApiContract bundled()`
  - `SchemaNode request(String endpoint)`, `SchemaNode response(String endpoint)`
  - `boolean hasEndpoint(String endpoint)`

- [ ] **Step 1: Write the failing test**

```java
@Test
void aPropertyTreeIsReadWithItsEnumValues() {
	ApiContract contract = ApiContract
			.load(Path.of("src/test/resources/contract-fixture.json"));

	SchemaNode request = contract.request("PATCH /example/{id}");

	assertThat(request.properties()).containsKey("merge_commit_title");
	assertThat(request.properties().get("merge_commit_title").enumValues())
			.containsExactlyInAnyOrder("PR_TITLE", "MERGE_MESSAGE");
}
```

The fixture holds one endpoint with a nested object, an array, an enum and a
discriminated `oneOf` — hand-written, so the checker's own tests never depend
on the 483 KB file.

- [ ] **Step 2: Run it, see it fail**

Run: `./mvnw test -DskipNativeTests -Dtest=ApiContractTest`

- [ ] **Step 3: Implement `ApiContract`**

Plain Jackson `JsonNode` walking into the record above. An absent key becomes
`null`/empty, never throws.

- [ ] **Step 4: Run it, see it pass. Commit.**

---

### Task 4: `WireShape` — what a record declares

**Files:**
- Create: `src/test/java/io/github/arlol/githubcheck/client/WireShape.java`
- Create: `src/test/java/io/github/arlol/githubcheck/client/WireShapeTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `record Property(String wireName, JavaType type)`
  - `static List<Property> properties(ObjectMapper mapper, JavaType type, boolean forReading)`
  - `static Set<String> enumValues(ObjectMapper mapper, Class<?> enumType)`
  - `static ObjectMapper clientMapper()` — the exact configuration
    `GitHubClient` builds
  - `static Map<String, Class<?>> subtypes(Class<?> polymorphic)` — discriminator
    value to subtype, read from `@JsonSubTypes`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aComponentNameIsTheNameJacksonWillSend() {
	List<String> names = WireShape
			.properties(WireShape.clientMapper(),
					TypeFactory.defaultInstance()
							.constructType(RepositoryUpdateRequest.class),
					false)
			.stream().map(WireShape.Property::wireName).toList();

	assertThat(names).contains("has_issues", "web_commit_signoff_required");
}

@Test
void anEnumValueIsWhatJacksonSerialisesNotTheConstantName() {
	assertThat(WireShape.enumValues(WireShape.clientMapper(),
			RulesetEnforcement.class))
			.containsExactlyInAnyOrder("active", "disabled", "evaluate");

	assertThat(WireShape.enumValues(WireShape.clientMapper(),
			MergeCommitTitle.class))
			.containsExactlyInAnyOrder("PR_TITLE", "MERGE_MESSAGE");
}
```

The second test is the whole point of the feature in miniature: one enum whose
values are lowercase through `@JsonProperty`, one whose values are uppercase
through nothing at all.

- [ ] **Step 2: Run them, see them fail.**

- [ ] **Step 3: Implement**

```java
BeanDescription description = forReading
		? mapper.getDeserializationConfig().introspect(type)
		: mapper.getSerializationConfig().introspect(type);
return description.findProperties().stream()
		.map(p -> new Property(p.getName(), p.getPrimaryType()))
		.toList();
```

`enumValues` serialises each constant with the mapper and strips the quotes.
`clientMapper()` duplicates `GitHubClient`'s four lines; a comment says that it
must, and why a divergence would make the check verify the wrong thing.

- [ ] **Step 4: Run them, see them pass. Commit.**

---

### Task 5: `ContractComparison` — the findings

**Files:**
- Create: `src/test/java/io/github/arlol/githubcheck/client/ContractComparison.java`
- Create: `src/test/java/io/github/arlol/githubcheck/client/ContractComparisonTest.java`

**Interfaces:**
- Consumes: `ApiContract.SchemaNode`, `WireShape`.
- Produces:
  - `record Finding(String endpoint, String direction, String path, String detail)`
  - `List<Finding> compare(SchemaNode schema, JavaType root, Options options)`
  - `record Options(boolean reverse, Set<String> unmanaged, Set<String> undocumented, Predicate<String> rootMetadata)`

- [ ] **Step 1: Write the failing tests**

One test per row of the spec's table, against `contract-fixture.json` and small
local records declared inside the test class:

```java
@Test
void aMiscasedComponentIsReported() { ... }          // forward: name
@Test
void aJavaEnumConstantTheSpecDoesNotCarryIsReported() { ... }
@Test
void aSpecEnumValueTheJavaEnumDoesNotCarryIsReported() { ... }
@Test
void aBooleanAgainstAStringPropertyIsReported() { ... }
@Test
void aPrimitiveAgainstANullablePropertyIsReported() { ... }
@Test
void aSpecPropertyTheRecordOmitsIsReportedOnlyInReverseMode() { ... }
@Test
void anUnmanagedEntrySuppressesOneFindingAndNoOther() { ... }
@Test
void anUndocumentedEntrySuppressesOneFindingAndNoOther() { ... }
@Test
void aStaleExclusionIsItselfAFinding() { ... }
@Test
void aSubtypeIsComparedAgainstItsOwnBranchNotTheUnion() { ... }
@Test
void theRootMetadataRuleDoesNotHideANestedUrl() { ... }
```

- [ ] **Step 2: Run them, see them fail.**

- [ ] **Step 3: Implement the walk**

Recursive over `(SchemaNode, JavaType, path)`, collecting findings:

1. **Forward.** Each declared property whose `wireName` is absent from
   `schema.properties()` is a finding, unless `undocumented` names the path.
2. **Reverse** (only when `options.reverse()`). Each spec property absent from
   the declared names is a finding, unless `unmanaged` names it, or the path is
   at depth 0 and `rootMetadata` matches it.
3. **Enums.** Both directions over the value sets, with `path#value` honoured
   by both exclusion lists.
4. **Type.** Only a contradiction: `boolean` against a non-boolean spec type,
   `string` against `integer`/`boolean`/`number`, a record against a
   non-`object`. `Map`, `JsonNode` and an absent spec `type` compare nothing.
5. **Nullability.** A Java primitive against `nullable: true` is a finding.
6. **Recursion.** Into a `client` record, into `List`/`Set` element types
   through `items`, and into `@JsonSubTypes` against `schema.oneOf()`.
7. **Stale exclusions.** Any `unmanaged` or `undocumented` entry no finding
   consumed is itself a finding.

A visited set of `type + path` guards the self-referencing case —
`TeamResponse.parent` is a `TeamResponse`.

- [ ] **Step 4: Run them, see them pass. Commit.**

---

### Task 6: Annotate the records

**Files:**
- Modify: all 58 `*Request` / `*Response` records in
  `src/main/java/io/github/arlol/githubcheck/client/`

- [ ] **Step 1: Annotate each record** with the endpoints its Javadoc and
  `GitHubClient`'s call sites say it serves.

- [ ] **Step 2: Re-run the extractor** so the contract file covers exactly the
  annotated set.

Run: `python3 download-schemas.py --contract`

- [ ] **Step 3: Commit.**

---

### Task 7: `GitHubApiContractTest` — the gate

**Files:**
- Create: `src/test/java/io/github/arlol/githubcheck/client/GitHubApiContractTest.java`

- [ ] **Step 1: Write the completeness guard**

```java
@Test
void everyWireRecordNamesItsEndpoint() { ... }
```

ClassGraph over `io.github.arlol.githubcheck.client`: every record whose name
ends in `Request` or `Response` either carries `@GitHubEndpoint` or is
reachable from one. Two named exclusions with reasons — `CachedHttpResponse`
(not wire JSON) and `GraphQlRepositoryResponse` (GraphQL, not OpenAPI).

- [ ] **Step 2: Write the contract gate**

```java
@TestFactory
Stream<DynamicTest> everyRecordMatchesTheEndpointItNames() { ... }
```

One dynamic test per (record, endpoint, direction). Forward always; reverse
when the direction is `request`, or when the endpoint is not on
`RESPONSE_REVERSE_PENDING`. Soft assertions, so one run lists everything.

- [ ] **Step 3: Write the endpoint-coverage guard**

An annotated endpoint absent from the contract file fails, naming the refresh
command. This is what closes the loop Task 2 left open.

- [ ] **Step 4: Run it and work through what it finds.**

Run: `./mvnw test -DskipNativeTests -Dtest=GitHubApiContractTest`

Every finding is triaged into exactly one of: a bug in a record (fix the
record), a field GitHub's spec lags on (`undocumented`, with the reason), or a
field drifty does not manage (`unmanaged`, with the reason). A finding is never
silenced by widening the metadata rule.

- [ ] **Step 5: Commit.**

---

### Task 8: Document it

**Files:**
- Modify: `CLAUDE.md`
- Modify: `FOLLOWUPS.md`

- [ ] **Step 1: CLAUDE.md** gains entries under "Adding or changing a managed
  setting": the annotation is a fourth place a new wire field appears; wire
  names come from Jackson and must not be re-derived; the metadata rule is
  root-level because `config.url` is a setting; `unmanaged` and `undocumented`
  are not interchangeable.

- [ ] **Step 2: FOLLOWUPS.md** gains entry 5 for
  `RESPONSE_REVERSE_PENDING` — what to delete, how to check.

- [ ] **Step 3: Run the full build.**

Run: `./mvnw verify`

- [ ] **Step 4: Commit and push.**

---

## Self-Review

**Spec coverage.** Contract file → Task 2. Annotation → Task 1, applied in
Task 6. Ask-Jackson → Task 4. Six comparison rows → Task 5. Metadata rule →
Task 5 (`rootMetadata`), exercised in Task 7. Completeness guard and its two
exclusions → Task 7. Two-stage rollout → Task 7's `RESPONSE_REVERSE_PENDING`
and Task 8's FOLLOWUPS entry. Checker's own tests → Tasks 3–5.

**Deviation from the spec, recorded deliberately.** The spec says the extractor
reads annotations off compiled classes via ClassGraph. Task 2 scans the source
for endpoint literals instead, because a Python script cannot read Java
annotations without a new build coupling, and Task 7 Step 3 closes the same
loop from the test side. The spec is amended to match when Task 2 lands.

**Type consistency.** `SchemaNode`, `Property`, `Finding` and `Options` are
each defined once and referred to by those names throughout.
