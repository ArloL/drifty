# Drift architecture

- **Reading state and comparing it are different classes.**
  `RepositoryStateReader` and `OrganizationStateReader` hold `fetchState`;
  `RepositoryChecker` and `OrganizationChecker` hold the comparison and the
  fix. A check builds a checker, which builds its own reader with
  `FetchFailures.STRICT`; `ExportRunner` builds a reader alone, with
  `FetchFailures.collecting()`, because an export reads and never compares.
  Adding a read goes in the reader, and the export gets it without being
  touched — which is the point of the split: the export used to construct a
  whole checker and pass three arguments chosen to neutralise the half it did
  not want.
- **One entity's report entry is built in `DriftReport`, for both scopes.**
  `groupDrifts` keeps a group only when it reported a drifted item — its keys
  are the `Would fix:` preview, and keying on "returned a fix" named groups
  that had no drift — and `entry` turns those into `OK`, `DRIFT` or the
  per-setting FIXED/FAILED of a `--fix` run. It is generic over the group-name
  enum, like `DriftGroup`, `ManagedGroups` and `Fanout`. A new field on the
  report goes here once; it used to go in both checkers, and three of them
  (`unmanaged`, the preview, the fix reports) did.
- **Drift groups never see GitHub response types.** The two readers'
  `fetchState` translate every `client/*Response` into
  an `actual/*` record through `ActualTypes` (the mirror of `PklTypes` on the
  desired side), and `RepositoryState`/`OrganizationState` hold only those. Put
  wire-shape knowledge — omitted sections, `{"status": "enabled"}` wrappers,
  nulls that mean `""` — in `ActualTypes`, not in a group.
  `ActualStateBoundaryTest` fails a group or state field that holds a client
  type other than `GitHubClient`, `RepoRef` or an enum.
- **A client enum is spelled by `ConfigSpelling`, never by `toString()`.**
  Three vocabularies name the same value — the Java constant
  (`ORGANIZATION_ADMIN`), GitHub's wire spelling (the `@JsonProperty`), and the
  schema's own literal (`"OrganizationAdmin"`) — and only the third may reach an
  `actual/*` field or an exported file, because the adopter loads that file back
  through Pkl. `ConfigSpelling.of` answers a generated `Drifty` constant rather
  than a string, so a misspelling does not compile and a renamed union member
  breaks the build. It replaced a rule that lower-cased the constant name, which
  is wrong for 13 of the 111 spellings GitHub uses and wrote
  `actorType = "ORGANIZATION_ADMIN"` into a file that would not then evaluate,
  and seven hand-written tables of string literals. A new enum-valued field adds
  an overload there and a pair to `ConfigSpellingTest`, which looks each union
  member up by the client constant's own name and so fails a mis-mapped arm.
  `Pages.buildType` is the one spelling with no `Drifty` constant to name: its
  union is written inline in the schema, so codegen leaves it a `String`.
- **Both directions of that spelling are pinned, and by different tests.**
  `ConfigSpellingTest` covers the read direction — a client constant the schema
  has no member for. `PklTypesTest` covers the write direction: every
  `PklTypes` mapping answers the client constant whose *name* matches the
  `Drifty` member's, so an arm answering the wrong constant of the right union
  (`ALWAYS -> PULL_REQUEST`) fails there. It has to be its own test, because
  the exhaustive `switch` catches only a missing arm and the four
  `valueOf(v.name())` mappings throw at runtime rather than at build time. A
  test that builds desired state from the schema carries a swapped value intact
  into the request body, so the run succeeds and GitHub is told to do something
  the config never asked for. The two spellings that reach GitHub as bare
  strings — `Pages.buildType` and `defaultRepositoryPermission` — are pinned
  against the vendored contract's own enum instead, since no client enum would
  notice them drifting.
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
  requests, guard them in the matching reader's `fetchState` too — filtering
  the group alone still sends
  them, and an account someone else administers is where those return 403.
