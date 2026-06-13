# drifty

Java CLI tool (`drifty`) that compares actual GitHub repository state
against desired configuration and reports or fixes drift.

See `SPEC.md` for the full specification and `FEATURES.md` for implementation
status.

## Building and running

```bash
./mvnw verify
./mvnw exec:java
```

## Native-image reachability metadata

The native image needs reflection/resource metadata for everything Jackson and
Pkl touch reflectively. It is **scope-split** so the shipped image stays lean:

- `src/main/resources/META-INF/native-image/reachability-metadata.json` —
  production scope (project records, Jackson, Pkl/Truffle, JNA/lazysodium, TLS).
- `src/test/resources/META-INF/native-image/reachability-metadata.json` —
  test-only scope (WireMock, Jetty, JMX/JFR, JUnit/surefire/AssertJ). The native
  *test* image sees both because test resources are on its classpath; the
  production image only sees the main file.

Do **not** commit the raw tracing-agent dump into the main file — it mixes
~100+ test-only entries into the shipped image. The caller-based
`access-filter.json` cannot remove them (the reflective calls originate in
JDK/JSSE code, not the test libraries). Instead, regenerate like this:

```bash
./mvnw test -Dagent=true                # retrace into target/native/agent-output
./mvnw test-compile                     # compile the tool onto the test classpath
./mvnw exec:java@reachability-metadata  # partition into the two scoped files
./mvnw -DskipTests package              # build + smoke-run the production image
./mvnw clean test                       # build + run the native test image
```

The splitter is `ReachabilityMetadata` (a `main` in `src/test/java`, so it uses
test-scoped ClassGraph without shipping it). It partitions by a conservative
denylist of test-only packages — anything not clearly test-only stays in the
production file, so a mis-classification can only keep a redundant entry, never
drop a needed one — and then augments the production file with every public
`client`/`pkl` record via ClassGraph so the project's own Jackson/Pkl types are
always registered even if untested.

Note: don't pass `-Dexec.arguments` on a full lifecycle invocation — it would
leak into the phase-bound `pkl-codegen-java` exec execution. The splitter needs
no arguments; it reads the default agent-output path.

## Downloading GitHub API schemas

`download-schemas.py` downloads the official GitHub REST API OpenAPI spec and
extracts per-endpoint schemas and example responses into `schemas/` for local
reference.

### Source

GitHub publishes their OpenAPI spec at
[github/rest-api-description](https://github.com/github/rest-api-description).
The script uses the **dereferenced** variant so all `$ref` links are resolved
to inline values, meaning example responses contain real data rather than
pointers.

### Running the script

```bash
# Default: 2026-03-10 spec, repo/org/user endpoints
python3 download-schemas.py

# Different API version
python3 download-schemas.py --api-version 2022-11-28

# Add extra path prefix (replaces the defaults)
python3 download-schemas.py --filter /repos/{owner}/{repo}/branches

# Custom output directory
python3 download-schemas.py --output-dir /tmp/schemas
```

### Output structure

`schemas/` is gitignored (the full run produces ~900 files, ~83 MB).

```
schemas/
├── openapi.json                                        # Full dereferenced spec
├── orgs/{org}/repos/
│   ├── get/
│   │   ├── schema.json                                 # Endpoint definition
│   │   └── example-200-default.json                   # Example response
│   └── post/
│       └── schema.json
├── repos/{owner}/{repo}/
│   ├── get/
│   │   ├── schema.json
│   │   └── example-200-default-response.json
│   └── patch/
│       ├── schema.json
│       └── example-200-default.json
├── repos/{owner}/{repo}/branches/{branch}/protection/
│   ├── get/
│   ├── put/
│   └── delete/
├── repos/{owner}/{repo}/actions/permissions/workflow/
│   ├── get/
│   └── put/
└── ...
```

Each `schema.json` contains the full OpenAPI operation object: `summary`,
`parameters`, `requestBody` (with JSON schema), and `responses` (with JSON
schemas). The `example-*.json` files are the extracted inline examples from
the spec — useful as realistic test data or for verifying that Java records
cover all fields.

### Default path prefixes

The script filters paths starting with:

- `/repos/{owner}/{repo}` — all single-repository endpoints (~460 operations)
- `/orgs/{org}/repos` — list and create org repositories
- `/user/repos` — list and create authenticated-user repositories
