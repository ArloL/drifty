# drifty

Java CLI tool (`drifty`) that compares actual GitHub organization and
repository state against desired configuration and reports or fixes drift.

See `SPEC.md` for the full specification and `FEATURES.md` for implementation
status.

## Building and running

```bash
./mvnw verify
./mvnw exec:java
```

## Adding or changing a managed setting

- **Drift groups never see GitHub response types.** `RepositoryChecker.fetchState`
  and `OrganizationChecker.fetchState` translate every `client/*Response` into
  an `actual/*` record through `ActualTypes` (the mirror of `PklTypes` on the
  desired side), and `RepositoryState`/`OrganizationState` hold only those. Put
  wire-shape knowledge — omitted sections, `{"status": "enabled"}` wrappers,
  nulls that mean `""` — in `ActualTypes`, not in a group.
  `ActualStateBoundaryTest` fails a group or state field that holds a client
  type other than `GitHubClient`, `RepoRef` or an enum.
- **Repositories nest under the account that owns them.** `config/drifty.pkl`
  has `organizations` and `users`, both keyed by login; the key is the owner and
  `Repository` has no `owner` field. `RepositoryState.ref()` is what carries the
  owner from there into the client calls.
- **Test fixtures for desired state come from the schema.** `testsupport.Desired`
  evaluates `src/test/resources/desired-defaults.pkl` once and hands out
  `Drifty.*` instances carrying `config/drifty.pkl`'s defaults; tests change
  fields with the generated `withX` methods. Do not reintroduce hand-written
  `*Args` builders — a new Pkl field needs no test-side change.
- **A new drift group needs a name constant in its own scope.** Repository
  groups name themselves in the `GroupName` typealias in `config/drifty.pkl`,
  organization groups in `OrgGroupName`, and `DriftGroup<N>` is generic over
  the enum so neither scope can use the other's names.
  `DriftPathNamespacingTest` fails a group whose constant is missing from
  either union, and a `Managed.groups` or `OrgManaged.groups` entry naming a
  group that does not exist fails at config eval. If the group sends its own
  requests, guard them in `RepositoryChecker.fetchState` or
  `OrganizationChecker.fetchState` too — filtering the group alone still sends
  them, and an account someone else administers is where those return 403.
- **`GET /orgs/{org}` is sent even when `org_settings` is unmanaged.** It is how
  `OrganizationChecker.fetchState` learns the organization exists — a 404 there
  is what makes the entry `MISSING` — and any member can read it. Every other
  org request is guarded by its group.
- **Eight groups PATCH the same `/repos/{owner}/{repo}` resource.** Each
  request carries only its own fields because `RepositoryUpdateRequest` is
  all nullable wrappers under `NON_NULL`; keep it that way.
- **`RepoSettingsDriftGroup` sends only the fields that drifted.** Its
  `Setting` table pairs each comparison with the builder call that writes it,
  and the PATCH is built from the drifted entries alone. Building the body
  from `desired` instead is the shape to avoid: it sent `allow_forking` on
  every org-owned repository, and an org with
  `members_can_fork_private_repositories` off answers that field with a 422
  even when it already holds the wanted value, failing a description change
  over a setting that had not drifted. A `Setting` with a null `write` is
  reported but never sent — `visibility` is the only one on the repository
  side, per SPEC.md.
- **`OrgSettingsDriftGroup` is the same shape for the same reason.** Its PATCH
  carries only the drifted fields and re-sends per field on a 422, because
  `members_can_create_internal_repositories` is the org-side `allow_forking`:
  GitHub rejects it on any organization outside Enterprise even when it already
  holds the wanted value. Ten of its settings have a null `write` — `GET
  /orgs/{org}` returns them and the PATCH accepts none of them.
- **A rejected PATCH is not a failed PATCH.** GitHub applies the fields it
  accepts and rejects the rest, so a 422 attributes to no field. When a
  multi-field request fails, `RepoSettingsDriftGroup` re-sends each field on
  its own and reports only the ones that fail again. Collapsing that back to
  "the request threw, so nothing was fixed" is what made a run report every
  setting unfixed after GitHub had already changed most of them.
- `./mvnw test` also builds and runs the native test image when GraalVM is
  the JDK. Iterate with `-DskipNativeTests`, run the full thing once before
  pushing.

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
test-scoped ClassGraph without shipping it). Both reflection and resources are
partitioned by a production **allowlist**, with everything else supplied by the
GraalVM metadata repository and routed to test scope:

- reflection: only `io.github.arlol.*` types and the `com.goterl.lazysodium` /
  `com.sun.jna` binding (257 entries; everything else is repository-supplied);
- resources: only Pkl's own resources and a platform-agnostic `**/libsodium.*`
  glob for the lazysodium native library (11 entries).

`io.github.arlol.*` is a package prefix, not a record filter: `DriftyState` and
its `RepoState`/`OrgState` inner classes are plain classes and are matched the
same way. Their metadata comes from what the suite traces, so a field or an
inner class Jackson only touches on a code path no test exercises is silently
absent from the shipped image — `StateStoreTest` round-trips both a repository
and an organization secret record for that reason.

The main file is then augmented with every public `client`/`pkl` record via
ClassGraph so the project's own types are registered even if untested.

The reflection allowlist was established empirically: the production image was
rebuilt with progressively fewer entries and smoke-tested (Pkl load + TLS to
GitHub), while the native test suite (Jackson round-trips + libsodium crypto)
guarded the rest. Removing the JNA/lazysodium entries breaks `SecretsTest`
(`UnsatisfiedLinkError: sodium_init`), which is why they stay.

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
