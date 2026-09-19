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

The repository's own Pkl files are held to `pkl format` as well — `main.yaml`'s
`pkl-format` job fails the build on any tracked one the formatter would rewrite,
which is the same rule the exporter follows for the file it writes (below).
Fix them with:

```bash
git ls-files -z '*.pkl' | xargs -0 pkl format -w
```

## Releasing

- **A release asset's name is the installer's only input.** mise scores assets
  by the os and arch tokens in the name, prefers an archive over a bare file,
  and then runs whatever the archive holds under the name it already has. So
  `main.yaml`'s release job builds one archive per os and arch holding a single
  executable called `drifty`. Naming an asset for the os alone is what made
  `mise use github:ArloL/drifty` unpack a zip of jars with no `drifty` in it,
  and would hand an arm64 machine an x64 build.

## Loading the config

- **The schema is answered from inside the binary, not fetched from GitHub.**
  Every exported config `amends` `BundledSchema.MAIN_SCHEMA_URI`, and Pkl's
  cache under `~/.pkl/cache` holds packages only — a plain `https` module is
  fetched again on every evaluation. Measured on a 477-line config: 0.21s
  evaluated against the URL, 0.07s against a local copy, which was the whole of
  drifty's config-loading phase. `BundledSchema.moduleKeyFactory` claims that
  one URI and Pkl's own `https` factory answers everything else, so a config
  pinned to a tag still resolves over the network. It has to be *prepended* to
  the preconfigured factories, not added: the first factory that claims a URI
  is the one that answers it. Both `PklConfigLoader` and `SchemaDefaults` build
  their evaluator from `BundledSchema.evaluatorBuilder`.
- **The bundled schema is `config/drifty.pkl`, copied by the build.** The
  `pkl-add-resource` execution in `pom.xml` puts it beside `BundledSchema`, and
  its glob is in both `reachability-metadata.json` and
  `ReachabilityMetadata.MAIN_RESOURCE_PREFIXES` or the native image cannot read
  it. Answering with the shipped copy is the point: `Drifty.java` is generated
  from that same file at build time, so a binary resolving main's newer schema
  could be handed a field its own mapper has never heard of.

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
- **Two repository endpoints exist only under an organization.** `GET
  /repos/{owner}/{repo}/teams` and `GET /repos/{owner}/{repo}/properties/values`
  answer 404 on a user-owned repository whatever the token can do, so
  `RepositoryChecker.fetchState` guards both on
  `ActualRepository.organizationOwned()` — which is why
  `ActualTypes.repository(details)` is built before them, not after.
  `CustomPropertiesDriftGroup` takes the same flag and reports the properties
  the config names as unfixable there instead of sending a PATCH that 404s,
  the way `CollaboratorsDriftGroup` does for team access. A new
  organization-only endpoint takes that shape too: the guard alone turns an
  abort into false drift and a fix that always fails.
- **`GitHubClient` bounds its own in-flight requests, not the checkers.** The
  checkers start one virtual thread per repository and `fetchState` starts more
  under each, so nothing above the client knows how many requests are in
  flight; GitHub's single HTTP/2 connection caps concurrent streams at 100 and
  the JDK client throws `IOException: too many concurrent streams` rather than
  queueing, which killed a run over ~100 repositories before it checked
  anything (issue #137). The semaphore sits in `sendBounded` because every
  caller shares that one connection — a bound around the per-repository threads
  would leave the export path and any future parallelism unguarded.
  `sendBounded` declares the checked exceptions rather than catching them so
  `sendRequest` keeps the one pair of arms, and the permit is gone before any
  rate-limit pause, so a thread parked until the reset is not holding a stream.
  `GitHubClientConcurrencyTest` fails if more requests overlap than the limit.
- **The ninety permits are a margin, and `--max-concurrent-requests` is how
  it gets spent.** GitHub documents a hundred concurrent requests;
  `MAX_CONCURRENT_REQUESTS` is ninety so that something else using the same
  token is not refused because of drifty. Interleaved against each other three
  times on the 101-repository `ArloL` account, ninety averaged 2.23s and a
  hundred 1.97s with no refusal — so the margin is worth 12%, and whether it
  can be spent is the operator's question, not drifty's. `GitHubCheck` refuses
  a value past `CONCURRENCY_CEILING` rather than letting GitHub do it.
- **The connection is opened before there is anything to send on it.** A check
  starts ninety requests at once and every one of them waits out DNS, TCP and
  TLS: the first wave answered in 413ms against the 237ms the rest of the run
  saw. `GitHubClient.warmUp` sends an unauthenticated `HEAD /` — the pool is
  keyed by host, not by credentials — and `main` calls it before evaluating
  the config rather than after, so the handshake and the Pkl evaluation
  overlap. Worth ~20ms against a config that evaluates in 30ms, and more
  against a larger one; the ceiling on it is however much work there is to do
  before the first request.
- **`fetchState` fans out; two levels, never three.** `Fanout` starts every
  read that needs nothing at once, and only five wait: an environment's
  policies/secrets/variables, and `/teams`, `/properties/values` and `/pages`,
  which wait on `GET /repos/{owner}/{repo}` — the owner's type is what says
  whether to send the first two at all, and `has_pages` whether there is a site
  to ask about.
  Issuing them in series made the deepest single repository the
  floor on the whole run — 24 requests and 6.6s of a 9.5s check, with the
  semaphore idle. A group whose read waits on another group's read puts the
  chain back; `RepositoryCheckerRequestShapeTest` fails on a depth over 2 and
  on a per-repository request count that changed. Two things follow: join
  `details` first and outside every `failures.read`, so a repository whose own
  details failed does not report that failure under some group's name, and keep
  `FetchFailures.Collecting` synchronized and sorted, because one repository's
  groups now fail on different threads and an export has to be byte-identical
  twice running.
- **One GraphQL query answers four groups, and it is written as four.**
  `GitHubClient.graphqlRepository` reads a repository's rulesets and their
  rules, its branch protections, its collaborators and its vulnerability-alerts
  flag in one request where REST needed six and two levels of waiting — 268 of
  a 101-repository check's 742 requests. Each of the four is its own aliased
  `repository` selection because GitHub nulls the whole `repository` object
  when one field inside it is forbidden: one alias each is what keeps a token
  that may not read branch protection from losing the rulesets too, and
  `GraphQlQuery.read` fails only the section whose error path names it. A fifth
  thing to read belongs in a fifth alias, not inside an existing one.
- **The GraphQL answer is rewritten as the REST shape, not as new records.**
  `GraphQlShape` turns each node into the JSON `RulesetDetailsResponse`,
  `BranchProtectionResponse` and `CollaboratorResponse` already parse, so
  `ActualTypes` stays the one translator and every test over it still guards
  both routes. Verified against all 45 active repositories of one account: the
  `ActualRuleset` and `ActualBranchProtection` the two routes produce were
  identical for every one. Most of it is camelCase against snake_case and
  `SCREAMING_CASE` against the wire spelling; four things are not, and each has
  produced a wrong answer rather than an error when guessed —
  `lockAllowsFetchAndMerge` is `allow_fork_syncing`, a ruleset's status checks
  name their app as `integrationId` where a branch protection rule's use
  `app { databaseId }`, `MergeQueueParameters` spells two fields `...Minutes`
  that REST does not, and a bypass actor is a union plus three booleans where
  REST has an `actor_type` string.
- **The query asks `includeParents: false` and still reads `source`.** An
  organization's ruleset the repository endpoint cannot delete is not the
  repository's to reconcile, and `RepositoryChecker.rulesets` filters it out by
  `sourceType` the way the REST path always did. Hardcoding `source_type` to
  `Repository` would mislabel one and produce a fix that always fails.
- **An organization's budget is three round trips, one more than a
  repository's.** `OrganizationChecker.fetchState` shares the same `Fanout`,
  and the extra level is `GET /orgs/{org}`: it is how the checker learns the
  organization exists, so every group waits on it rather than firing two dozen
  requests at a login GitHub has never heard of. Below it each group's listing
  starts at once, and only a ruleset's rules, a team's two member listings, a
  configuration's repositories and defaults, the repositories behind a
  `selected` secret, variable or runner group, and the allow-list and
  repository selection behind `selected` Actions permissions wait — one round
  trip, never two. `OrganizationCheckerRequestShapeTest` fails on a fourth.
  The defaults listing is the one to be careful with: it looks independent of
  the configuration listing, but starting it eagerly spends a request on every
  organization that has no configuration and fails the group on a token that
  cannot read it, so it hangs off the listing beside the per-configuration
  reads instead.
- **The organization is checked before its repositories, and `--fix` needs
  that order.** Each phase is fanned out internally, but they are not run
  against each other: a repository cannot be granted access to a team that
  does not exist yet, cannot carry a value for a custom property whose
  definition does not exist yet, and a code security configuration attaches to
  repositories whose own security groups write the same settings. Overlapping
  the two phases is the obvious next speed-up and it is not available in fix
  mode; doing it for check mode alone would make the two modes send different
  request schedules, which is how a fix-only ordering bug stays invisible.
- **A rate limit is not a failed request.** `sendRequest` re-sends through a 403
  or 429 that carries `Retry-After` or `X-RateLimit-Remaining: 0`, and pauses
  (without re-sending) after a good response that spent the last of the budget.
  A 403 with neither is a token without a scope and has to keep reaching
  `FetchFailures` as the failure it is — that distinction is the whole of
  `rateLimited`. Reporting is deduplicated per pause window because up to
  `MAX_CONCURRENT_REQUESTS` threads hit the same reset within milliseconds of
  each other; before it existed an exhausted budget parked a run for eight
  minutes in silence. `GitHubClientRateLimitTest` covers each shape.
- **The secondary limits are paced against, not retried into.** GitHub allows
  900 points a minute on the REST API — a read costs one, anything that writes
  costs five — answers 403 or 429 with `Retry-After` past that, and reports the
  budget in no header. `RequestPacer` books a request's points in `sendBounded`
  before it takes a stream permit, scheduling it so that no 60-second window
  holds more than 900: an account too big for the limit becomes a slower run
  rather than a refused one. A run that fits in the window waits nowhere — the
  742 points a 101-repository check costs are all due immediately — so
  measuring the pacer against a small account shows it doing nothing, which is
  the design and not evidence that it can go. The response cache is no help
  here either: a 304 is still a request and still costs its point. Three things
  follow. The wait is taken before the permit, so a paced thread is not sitting
  on one of the ninety streams. `sendRequest` hands every pause to `backOffFor`
  as well as sleeping it out, because `Thread.sleep` stops one thread and the
  limit belongs to the token — the other eighty-nine would spend the pause
  earning refusals of their own, and the three attempts would go to requests
  that never had a chance. And a point the gate held back is booked where it
  lands, not where it was asked for, or the window empties itself while the run
  is parked and the whole budget goes out the moment it lifts.
- **A listing's pages after the first go out together.** `remainingPages` reads
  the page count off the `Link` header's `rel="last"` and asks for 2..N at
  once, falling back to the `rel="next"` walk when GitHub sends no `rel="last"`
  — the final page of a listing, and any cursor-paginated endpoint. The walk
  paid a round trip per page in series before anything else could start.
  Matching a `page` parameter takes the leading `?` or `&` — every listing URL
  also carries `per_page`, which contains the same six characters.
- **An archived repository costs the check nothing and the export one
  request.** When `desired.archived` is true `checkOne` compares
  `summary.archived()` off the account listing and never calls `fetchState`:
  `createDriftGroups` returns `ArchivedDriftGroup` and no other, and that group
  needs the one boolean the listing already carries. The export cannot follow —
  `RepositoryExporter.entry` writes an archived repository's settings down even
  though it compares none of them, and the listing omits the merge fields — so
  `ExportRunner` still reads `GET /repos/{owner}/{repo}` under
  `RepositoryChecker.ARCHIVED_ONLY`, and `collectMissingSecrets` narrows the
  same way so `--fix` is not aborted over a secret nothing will write. The
  check side keys on `desired.archived`, not on `actual`: a repository that is
  archived while the config wants it active still reads everything, because
  unarchiving is about to make all of it apply. The narrowing is not a
  `managed` declaration — the report's `unmanaged` list comes from the config's
  own block, or an `archived = true` repository's report would suddenly list
  every other group. Of one 101-repository account's 1152 requests, 343 went to
  groups nothing compared, and 51 more to details only one field of which was
  read.
- **A per-repository read the details response already answers is not sent.**
  `has_pages` is what `GET /repos/{owner}/{repo}/pages` answers with a 404 — 37
  of one account's 45 active repositories — so `fetchState` asks only when the
  details response says there is a site. It is read there rather than off the
  account listing, which the check no longer waits for.
  `security_and_analysis.dependabot_security_updates` on `GET
  /repos/{owner}/{repo}` is the same bit as `/automated-security-fixes`, which
  is why `SecurityFlags` has four members and not five; `paused` is the only
  field the endpoint carries that the details do not and nothing compares it.
  Before adding a read for a new group, diff what it returns against
  `schemas/repos/{owner}/{repo}/get/` and against the listing — one request per
  repository is ~0.3s of a run per 90 repositories.
- **The config says which repositories to check; the listing runs beside
  them.** `RepositoryChecker.check` submits every repository the config
  declares and does not want archived before it joins
  `GitHubClient.listUserReposAsync`, which is asking for the listing on its own
  virtual thread. Waiting for that listing was 596ms of a traced 3.09s fetch
  with fewer than ten requests in flight. The listing still answers the two
  questions only it can — what GitHub lists that the config does not declare
  (`UNKNOWN`), what it declares that GitHub does not list (`MISSING`) — plus
  `archived` for a repository the config wants archived. Two things follow.
  `fetchState` takes `archived` and `publicRepository` from the config rather
  than from a response, because reading either off `GET
  /repos/{owner}/{repo}` would put `/branches` behind it and each branch's
  protection behind that; a declared repository GitHub does not have spends its
  requests on 404s before the listing says so, which is the abnormal case. And
  a failing listing now fails inside `repoChecker.check`, which is why
  `GitHubCheck`'s user arm wraps both calls in one try — `checkOne` catches
  `GitHubApiException` itself, so nothing a repository does reaches that arm.
  The organization path keeps `listOrgRepos`: `OrganizationChecker.check`
  resolves a selected secret's repository ids against the whole listing, and it
  runs before the repositories anyway.
- **The semaphore is the floor, so request count is the only lever left.**
  Tracing a check of the 101-repository `ArloL` account on 2026-09-19: 742
  requests before the GraphQL query replaced 268 of them, p50 latency 249ms,
  and 77% of a 2.57s fetch window with 90 in flight. Wall clock is `requests × latency ÷ 90`, and every other term is at
  its limit. Latency is GitHub's, and roughly two thirds of it is the token:
  `GET /zen` resolves no permissions and reads no repository, and answers in
  95ms unauthenticated against 286ms authenticated over a 40ms round trip. That
  ~190ms is charged to every request whatever it asks for, and a fine-grained
  PAT and an OAuth token pay it alike — which is why dropping a request is
  worth so much more than making one cheaper. The permit count is at GitHub's
  documented 100-concurrent ceiling: 120 simultaneous requests all answered
  200, 150 drew 11×403 and 270 drew 135×403. Depth is held at two levels by
  `RepositoryCheckerRequestShapeTest`. Conditional requests are not a lever
  either: a 304 costs what the 200 it replaces costs, so the response cache
  buys rate-limit budget and no time. `FINDINGS.md` carries the measurements
  and what is left to take.
- **A GET drifty has seen before is asked conditionally, and GitHub does not
  charge the 304.** `GitHubClient.get` sends `If-None-Match` from the
  `ResponseCache` the state file implements, and `CachedHttpResponse` hands the
  kept body back as a 200, so the forty typed methods above it never learn
  anything was cached. Measured: `x-ratelimit-used` held across two 304s and
  moved on the next 200. Three things this does not do. It does not reduce
  request count, so it buys nothing against the secondary limits. It does not
  honour `cache-control: max-age=60` — drifty revalidates every read, or a
  `--fix` could read the state it had just written. And it cannot serve a
  response the current token may not read: the conditional request still
  carries the token, so a downgraded one is answered 403, never 304.
- **A 304 drops the `Link` header, so the cache keeps it.** `remainingPages`
  reads a listing's page count off `rel="last"`. Restore the cached header on
  the synthesised response, or a cached first page ends the listing there and
  drifty checks the first 100 repositories of an account and reports the rest
  as MISSING — no error, no failed request, a wrong answer.
  `GitHubClientCacheTest.aCachedListingStillReachesItsSecondPage` fails by
  returning one repository instead of two.
- **`GitHubCheck.main` saves state on every check, not only under `--fix`.**
  An earlier version gated `stateStore.save(...)` on `fix`, which was right
  while the file held only secret baselines — a plain check wrote none. It
  now also holds the response cache, so gating the save left every check
  cold: nothing a read filled in ever reached disk. Do not reintroduce the
  guard; the test that would have caught its return was deliberately removed
  for being tautological, so nothing else will. This is also why
  `StateStore.save` writes through a temp file and moves it into place
  atomically instead of truncating the target: a run that saves on every
  check, not only the occasional `--fix`, is a run where a Ctrl-C or a full
  disk mid-write is no longer rare enough to ignore.
- **The repository listing is not a substitute for `GET
  /repos/{owner}/{repo}`.** Dropping the per-repository details request is the
  obvious way to save one request per repository and it does not work: diffing
  the two payloads, the listing is a `Minimal Repository` and omits
  `allow_*_merge`, `allow_auto_merge`, `allow_update_branch`,
  `merge_commit_*` and `squash_merge_commit_*` — most of what
  `RepoSettingsDriftGroup` compares, so every repository would report drift on
  all of them. `security_and_analysis` is in the schema for the organization
  listing but `GET /user/repos` returned it on none of 101 repositories, so
  the security micro-groups cannot read it there either. The check reads only
  `archived` off it now, and only for a repository the config wants archived:
  anything else a repository needs would put the listing back in front of every
  one of them.
- **`RepositoryChecker.entry` catches `GitHubApiException`.** It runs inside
  a virtual thread whose `Future.get()` nothing above `check` handles, so
  without that arm one repository's 403 ends the run for every repository after
  it — the shape of issue #135. `OrganizationChecker.checkOne` has the same
  arm; keep both.
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
  `OrgSettingsDriftGroupTest.everyWritableSettingWritesTheFieldItCompared`
  reads its cases out of the table, so a new row needs no test change and a row
  whose builder call writes a different setting than it compared fails it.
- **A rejected PATCH is not a failed PATCH.** GitHub applies the fields it
  accepts and rejects the rest, so a 422 attributes to no field. When a
  multi-field request fails, `RepoSettingsDriftGroup` and
  `OrgSettingsDriftGroup` re-send each field on its own and report only the
  ones that fail again. Collapsing that back to
  "the request threw, so nothing was fixed" is what made a run report every
  setting unfixed after GitHub had already changed most of them.
- **The team POST and PATCH need different bodies.** `TeamRequest` serializes
  `parent_team_id` even when null because that is how the PATCH removes a
  parent, but `POST /orgs/{org}/teams` answers 422 to that null instead of
  reading it as "no parent", so no top-level team could be created.
  `TeamCreateRequest` is the same fields with the field omitted when null, and
  `createTeam` takes only that record. Do not collapse the two back into one.
- **`github-pages` is GitHub's environment, not the config's.** GitHub creates
  it the moment a Pages site is published, so
  `EnvironmentConfigDriftGroup` skips it in the extra-environment scan on a
  repository whose config declares `pages` — otherwise every repository that
  asks for Pages drifts against that same config (issue #157). The skip is
  conditional on `desired.pages != null` rather than unconditional so the
  environment a turned-off site left behind is still reported, and it does not
  consult `ManagedGroups`: the declaration is what says the site is wanted. An
  environment the config declares is compared like any other, `github-pages`
  included.
- **`--fix` deletes only what the config can recreate.** Rulesets, branch
  protections, webhooks, deployment branch policies and non-default runner
  groups are deleted when extra; secrets, variables, environments, custom
  property definitions, code security configurations and every kind of
  membership are reported with a reason and left alone. A new keyed group
  picks one of the two and says why in its class comment; SPEC.md's "What
  `--fix` Deletes" lists both sets.
- **An item drifty reports but never writes is `DriftFix.reported`.** The
  `Would fix:` preview names the groups holding at least one fix that can act,
  so a `new DriftFix(item, () -> FixResult.unfixed(...))` built by hand is
  counted as writable and offers a run that would do nothing — check mode
  printed `Would fix: collaborators` beside an extra collaborator `--fix`
  leaves in place (issue #156). `DriftGroup.detect` rebuilds every fix to
  namespace its paths and has to carry the flag through; dropping it there
  silently makes every group actionable again.
- **The account that owns a repository is not one of its collaborators.**
  `affiliation=direct` lists a personal account's owner, as admin, and
  `ActualTypes.collaborators` drops it — the grant is the ownership and no PUT
  or DELETE touches it. Keeping it made every personal repository drift on an
  extra collaborator and wrote the owner into every exported repository.
  `CollaboratorsDriftGroup` reports a config that names the owner instead of
  sending a PUT GitHub answers 422, the way it does for a team on a personal
  repository.
- **Enterprise-owned entities never reach a group.** The checker drops
  rulesets whose `source_type` is `Enterprise`, custom properties whose
  `source_type` is `enterprise`, teams whose `type` is `enterprise` and code
  security configurations whose `target_type` is `global` before building
  the state, the way `RepositoryChecker.fetchRulesets` drops organization
  rulesets. Reporting them as extra produces a fix that always fails.
- **`Ruleset` is `open` so `OrgRuleset` can extend it.** The generated
  `Repository.rulesets` is therefore `Map<String, ? extends Ruleset>`; a
  method taking the repository's rulesets declares that wildcard, and
  `RulesetComparison` takes a `Drifty.Ruleset` so both groups share it.
- **A `push` or `repository` ruleset has no ref-name condition.**
  `RulesetComparison.refName` returns null for those targets and the request
  omits the condition (`RulesetRequest.conditions` is `NON_NULL`, since a
  repository push ruleset has no conditions object at all). The schema
  refuses `includePatterns`/`excludePatterns` on them and refuses the
  `repository` target in `Repository.rulesets`, so neither case reaches a
  group. Which rules a target accepts is not checked anywhere: GitHub's 422
  is the report.
- **Code security option sub-objects are nullable, and null means unmanaged.**
  `codeScanningDefaultSetupOptions`, `codeScanningOptions` and
  `secretScanningDelegatedBypassOptions` are compared and sent only when the
  config sets them. Do not give them a default: GitHub picks a runner type
  itself when default setup is enabled, omits `code_scanning_options`
  entirely on a configuration that never set it, and a defaulted object would
  report drift on every configuration created with only a name.
  `dependencyGraphAutosubmitLabeledRunners` is the counter-example and is
  therefore flat, not a fourth object: GitHub returns
  `dependency_graph_autosubmit_action_options` on every configuration, its own
  included, so the field is compared and sent like any of the seventeen
  toggles. Which shape a new sub-option takes is decided by whether GitHub
  returns the object unconditionally, not by it being nested on the wire.
- **A drifted entry with several endpoints gets one `DriftFix` per
  endpoint.** `OrgActionsPermissionsDriftGroup` (policy, allow-list,
  repository selection), `OrgCodeSecurityConfigurationsDriftGroup` (settings,
  defaults, attachments), `OrgRunnerGroupsDriftGroup` (settings, selection)
  and `OrgTeamsDriftGroup` (settings, members, maintainers) all do this so a
  rejected write is not reported as having failed the others. A missing entry
  is the exception: its POST returns the id the other writes need, so one fix
  does all of them.
- **Names are resolved to ids at fix time, never at check time.** Repository
  names resolve through the id map the checker builds from the account's
  listing (`repositoryIds`); a name that is not in it fails the whole fix
  rather than writing a shorter list and reporting success. Logins and team
  slugs the environment group needs resolve with one request each, only when
  a fix runs.
- **A new CLI argument goes in three places.** `GitHubCheck.BOOLEAN_FLAGS` or
  `VALUE_OPTIONS` (so `unknownArguments` accepts it), `usage()` (so `--help`
  names it), and the branch in `main` that reads it.
  `usage_namesEveryArgumentDriftyAccepts` reads the two lists, so an accepted
  argument missing from the help text fails the build; an argument missing
  from the lists is refused with exit 1 instead of silently ignored, which is
  the whole point of issue #140. Nothing checks the third place — an argument
  in both lists that `main` never reads is accepted and does nothing.
- **The exported file is `pkl format` output, not merely valid Pkl.** An
  adopter commits it as their starting config and CI runs `pkl format
  --diff-name-only` over it, so `PklWriter` writes what the formatter would:
  an empty body is `{}` on the line that opens it, and a scalar assignment
  past `LINE_WIDTH` moves its value to its own line one level in. 100 is the
  formatter's width; the narrower `NOTE_WIDTH` is only where comments wrap,
  which is drifty's own choice — `pkl format` never rewraps one. Those two
  shapes are the whole of it: a block header, a listing element and a comment
  are left where they are however long they get, so do not bring them to 80
  columns too. Issue #138 was the export failing that check on its first CI
  run.
- **A new schema field needs an exporter line.** `SchemaCoverageTest` fails
  the build otherwise, and nothing else would: a field the export omits is one
  the config leaves at its default, so the round-trip test agrees with itself
  and passes.
- **A setting drifty reports but cannot write is exported as a field AND a
  note.** `OrgSettingsDriftGroup` compares a check-only setting like any
  other and only its writer is null, so a file that omitted the field would
  carry the schema default against GitHub's real value and report drift no
  `--fix` could ever clear. The field is what makes the file round-trip; the
  note is what tells a reader drifty will not change it. `visibility` on the
  repository side and the ten check-only organization settings are the
  cases.
- **A group the export could not read is exported as a `managed` exclusion AND
  a note.** `AccountExporter.addUnmanagedGroups` names every
  `FetchFailures.Failure` in the entry's own `managed` block; the note beside
  the section says why. The note alone is what issue #136 was: a `//` comment
  is the one form a later run cannot act on, so the exported file failed on
  exactly the request the export had already failed on. Emit the block before
  the archived branch in `RepositoryExporter.entry` —
  `RepositoryChecker.fetchState` reads a group for an archived repository too.
- **`schemas/` holds the endpoint shapes.** It is gitignored;
  `python3 download-schemas.py --filter '/orgs/{org}/...'` recreates the
  part you need. Check a new record's field names and enums there before
  writing it — `dependabot_delegated_alert_dismissal` exists,
  `code_security` is a separate field from `advanced_security`, and the
  collaborator listing carries `permissions` booleans beside `role_name`.
- `./mvnw test` also builds and runs the native test image when GraalVM is
  the JDK. Iterate with `-DskipNativeTests`, run the full thing once before
  pushing.
- **Native build time is mostly builder memory, and the macOS runner has 7GB.**
  `macos-latest` has 3 cores and 7GB, and the native test image is what makes
  its job the slowest by far; the builder's peak RSS decides whether that
  build runs or thrashes. `-H:+IncludeAllLocales` cost 2GB of it for ~49,000
  reflection-registered locale classes the tool never formats with, so it is
  gone from `native-image.properties`; do not bring it back for a
  locale-sensitive feature without measuring the test image on macOS. The
  test image builds with `quickBuild` (`-Ob`) because it verifies metadata,
  not speed; the production image stays at `-O2`. The build report's
  "registered for reflection" and "Peak RSS" lines are the numbers to watch.

## Native-image reachability metadata

- **Take the collection types Pkl's mapper already has metadata for.**
  `PklConfigLoader` walks the `organizations` and `users` mappings key by key
  and builds the `LinkedHashMap` itself, because
  `.as(LinkedHashMap<String, Organization>)` fails in the shipped binary:
  `PMapToMap` instantiates the raw target class reflectively, and
  pkl-config-java-native registers only `HashMap`, `ArrayList`, `HashSet`,
  `TreeMap`, `TreeSet` and `ArrayDeque`. Any other target maps fine on the JVM
  and ends a user's first run with
  `ConversionException: ... because no conversion was found`.
  `NativeExecutableIT.selfTestWithConfig` runs the real binary against
  `config/example.pkl` so that is a build failure instead.
  (`Mapping` fields inside the records are `Map`, which the mapper fills with a
  `HashMap` — do not rely on their iteration order.)
- **`--self-test` is what covers the shipped image.** It is the only place the
  production binary's reflective paths — libsodium through JNA, and a full Pkl
  evaluation when `--config` is passed — are exercised. Native *test* image
  runs do not: they see the test-scoped metadata too.

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
