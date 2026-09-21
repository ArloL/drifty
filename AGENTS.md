# drifty

Java CLI tool (`drifty`) that compares actual GitHub organization and
repository state against desired configuration and reports or fixes drift.

`SPEC.md` is the specification — read it for what a setting is meant to do and
for what `--fix` deletes. `FEATURES.md` tracks which settings are implemented.

## Building and running

```bash
./mvnw verify
./mvnw exec:java
```

Run `./mvnw clean verify` before pushing, not `verify`. Error Prone runs over
whatever javac compiles, and a non-clean build recompiles only what changed —
a finding in a file the build skipped is a finding you do not see until CI.
`./mvnw test` also builds and runs the native test image when GraalVM is the
JDK; iterate with `-DskipNativeTests` and run the full thing once before
pushing.

The repository's own Pkl files are held to `pkl format` — `main.yaml`'s
`pkl-format` job fails the build on any tracked one the formatter would
rewrite, which is the same rule the exporter follows for the file it writes.
Fix them with:

```bash
git ls-files -z '*.pkl' | xargs -0 pkl format -w
```

## Rules that hold everywhere

- **Null is a value drifty means, so the compiler checks it.** NullAway runs at
  `ERROR` over `src/main` only, in JSpecify mode, so `@Nullable` is a TYPE_USE
  annotation: on a qualified nested type it goes before the simple name
  (`ActualRuleset.@Nullable RulePattern`), not before the qualifier. Prefer
  saying what is true over silencing the checker; a guard the checker cannot
  see is `Objects.requireNonNull` with a reason, never a cast.
  (`docs/null-checking.md`)
- **Reading state and comparing it are different classes.**
  `RepositoryStateReader` and `OrganizationStateReader` hold `fetchState`;
  `RepositoryChecker` and `OrganizationChecker` hold the comparison and the
  fix. Adding a read goes in the reader, and the export gets it without being
  touched. (`docs/drift-architecture.md`)
- **Drift groups never see GitHub response types.** The readers translate every
  `client/*Response` into an `actual/*` record through `ActualTypes`, so
  wire-shape knowledge — omitted sections, `{"status": "enabled"}` wrappers,
  nulls that mean `""` — goes there and not in a group.
  `ActualStateBoundaryTest` fails a group field holding a client type.
- **One entity's report entry is built in `DriftReport`, for both scopes.** A
  new field on the report goes there once; it used to go in both checkers.
- **A client enum is spelled by `ConfigSpelling`, never by `toString()`.**
  Three vocabularies name the same value — the Java constant, GitHub's wire
  spelling, the schema's literal — and only the schema's may reach an
  `actual/*` field or an exported file. Both directions are pinned, and by
  different tests: `ConfigSpellingTest` reads, `PklTypesTest` writes.
- **Repositories nest under the account that owns them.** `config/drifty.pkl`
  keys `organizations` and `users` by login, so `Repository` has no `owner`
  field; `RepositoryState.ref()` carries the owner into the client calls.
- **Test fixtures for desired state come from the schema.**
  `testsupport.Desired` hands out `Drifty.*` instances carrying
  `config/drifty.pkl`'s defaults; change fields with the generated `withX`
  methods. Do not reintroduce hand-written
  `*Args` builders.
- **A new drift group needs a name constant in its own scope.** Repository
  groups name themselves in `GroupName`, organization groups in `OrgGroupName`,
  and `DriftGroup<N>` is generic over the enum so neither scope can use the
  other's names. If the group sends its own requests, guard them in the
  matching reader's `fetchState` too — filtering the group alone still sends
  them, and an account someone else administers is where those return 403.
- **`--fix` deletes only what the config can recreate.** Rulesets, branch
  protections, webhooks, deployment branch policies and non-default runner
  groups are deleted when extra; secrets, variables, environments, custom
  property definitions, code security configurations and every kind of
  membership are reported with a reason and left alone. A new keyed group picks
  one of the two and says why in its class comment.
- **`fetchState` fans out; two levels, never three.** A group whose read waits
  on another group's read puts back the serial chain that made the deepest
  single repository the floor on a whole run.
  `RepositoryCheckerRequestShapeTest` and `OrganizationCheckerRequestShapeTest`
  fail on the extra level and on a changed per-entity request count.
  (`docs/request-shape.md`)

## Where a change lands

**A new wire field appears in four places**: the reader that fetches it, the
group that compares it, the exporter line that writes it
(`SchemaCoverageTest` fails the build otherwise), and
`src/test/resources/github-api-contract.json` by way of the record's
`@GitHubEndpoint` (`docs/wire-contract.md`).

**A new CLI argument goes in three places**: `GitHubCheck.BOOLEAN_FLAGS` or
`VALUE_OPTIONS` so `unknownArguments` accepts it, `usage()` so `--help` names
it, and the branch in `main` that reads it.
`usage_namesEveryArgumentDriftyAccepts` reads the two lists, so an accepted
argument missing from the help text fails the build; an argument missing from
the lists is refused with exit 1 instead of silently ignored, which is the
whole point of issue #140. Nothing checks the third place — an argument in
both lists that `main` never reads is accepted and does nothing.

**A new schema field** needs a line in the exporter and nothing else on the
test side; `testsupport.Desired` picks it up from `config/drifty.pkl`.

## Reference documents

Each holds the invariants for one job, with the measurements and the dead ends
behind them. Read the one you are about to touch — they are not summaries of
the code, they are what the code does not say.

- `docs/drift-architecture.md` — how a check is wired: readers, checkers,
  `DriftReport`, `ActualTypes`, `ConfigSpelling`, group naming. Read before
  adding or changing a managed setting.
- `docs/drift-groups.md` — how a group compares and writes: `SettingTable`
  rows, a rejected PATCH against a failed one, which groups delete, the two fix
  convergence tests, `DriftFix.reported`. Read before writing a `--fix` path.
- `docs/request-shape.md` — which requests a check sends and what waits on
  what: the two-level fan-out, the GraphQL query that answers four groups, the
  per-repository reads that are not sent at all, what an archived repository
  costs. Read before adding a request per repository or organization.
- `docs/request-scheduling.md` — how those requests go out: the concurrency
  semaphore, the `RequestPacer` against the secondary limits, rate-limit
  retries, the conditional GET and the state file it caches into. Read before
  touching `GitHubClient`'s transport.
  `docs/performance/where-a-checks-time-goes.md` carries the measurements
  behind both.
- `docs/exporting.md` — the exported file is `pkl format` output, not merely
  valid Pkl: `PklWriter`'s three shapes, byte-identity across runs, the note
  and `managed` exclusion a failed read produces. Read before changing what an
  export writes.
- `docs/wire-contract.md` — `@GitHubEndpoint`, `WireShape`, and the
  `unmanaged` / `undocumented` exclusions that silence a finding with a reason.
  Read when adding a `client` record or when `GitHubApiContractTest` fails.
- `docs/github-api-schemas.md` — `download-schemas.py` and the gitignored
  `schemas/` tree. Read before writing a record's field names and enums from
  memory.
- `docs/native-image.md` — the scope-split reachability metadata, how to
  regenerate it, and why the production allowlist is what it is. Read when
  something reflects over a new type or the native test image fails.
- `docs/config-loading.md` — why the schema is answered from inside the binary
  rather than fetched. Read before touching `PklConfigLoader` or
  `BundledSchema`.
- `docs/null-checking.md` — the four NullAway rules and the five
  `Objects.requireNonNull` guards that exist. Read when the checker fails on a
  change you believe is correct.
- `docs/testing.md` — what mutation testing answers that coverage cannot, and
  the jacoco bound. Read before merging something that adds a table row.
- `docs/releasing.md` — a release asset's name is the installer's only input.
  Read before changing the release job.
