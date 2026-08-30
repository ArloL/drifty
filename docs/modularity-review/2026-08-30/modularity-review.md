# Modularity Review

**Scope**: Entire codebase — `src/main/java/io/github/arlol/githubcheck` (CLI, orchestration, 24 drift groups, GitHub REST client, state store) plus `config/drifty.pkl` and `src/test/.../testsupport`
**Date**: 2026-08-30

## Executive Summary

drifty compares the actual configuration of GitHub repositories against a desired state declared in Pkl, reports the difference, and with `--fix` reconciles it. The overall shape is sound: the project has a genuine [anti-corruption layer](https://coupling.dev/posts/related-topics/domain-driven-design/) between config and client types (`PklTypes`), a value-returning fix contract (`DriftFix` / `FixResult`), and a per-setting decomposition that has so far made adding settings cheap — the maintainer reports no pain to date. **The most important finding is that the contract between the 24 drift groups and the orchestrator is a rendered human-readable string, and this is not a stylistic concern: it is currently causing failed fixes to be reported as successes.** Thirteen groups emit the byte-identical drift message `enabled: want=true got=false`, and `OrgChecker.applyFixes` subtracts fixed items from the report with `List.removeAll`, which removes every equal element — so one successful security fix erases the drift lines of twelve other settings, including ones whose fix threw.

Two further imbalances are latent rather than active, but the stated direction — more managed settings, multi-org support, and GraphQL bulk reads — is precisely what will detonate them. GitHub REST response records are threaded unchanged through `RepositoryState` into every drift group, so a GraphQL read path cannot be introduced without touching all 24; and the repository owner is declared per-repo in config, ignored, and replaced by a `"ArloL"` literal that each of the 24 groups stores its own copy of.

## Coupling Overview Table

Level of abstraction analysed: **packages within a single deployable**. Per the model's [fractal geometry](https://coupling.dev/posts/core-concepts/balance/), the highest distance available here is the cross-package boundary; the GitHub API is a separate system (highest distance overall), and the `main` ↔ `test` source-root boundary sits between the two.

| Integration | [Strength](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | [Distance](https://coupling.dev/posts/dimensions-of-coupling/distance/) | [Volatility](https://coupling.dev/posts/dimensions-of-coupling/volatility/) | [Balanced?](https://coupling.dev/posts/core-concepts/balance/) |
| --- | --- | --- | --- | --- |
| `drift/*DriftGroup` → `OrgChecker` (fix accounting via `DriftItem::message`) | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), implicit | High (cross-package; effect only visible against live GitHub) | High — every new setting adds a group | **No — Critical** |
| `client/*Response` → `RepositoryState` → all 24 groups | [Model](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) (of the *transport* representation) | High (cross-package + external system) | High — GraphQL reads planned, API surface growing | **No — Critical** |
| Owner/org identity: `Drifty.Repository.owner` / `OrgChecker.org` / `"ArloL"` literal | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), duplicated + contradictory | High (CLI → orchestrator → 24 group constructors) | High — multi-org confirmed planned | **No — Significant** |
| `createDriftGroups` → `computeGroupDrifts` → `applyFixes` (archived-must-run-first) | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), implicit ([connascence of execution order](https://coupling.dev/posts/related-topics/connascence/)) | Medium (same class, but failure surfaces only at runtime) | Medium — `applyFixes` is the natural place to parallelise | **No — Significant** |
| `Drifty.Repository` aggregate → 21 of 24 groups | [Model](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | Medium (cross-package, generated + additive) | High — schema grows with every setting | **No — Minor** |
| "is this security flag enabled" rule, encoded 3× in `OrgChecker` | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), duplicated | Low (same class) | Medium | **No — Minor** |
| 8 groups → `PATCH /repos/{owner}/{repo}` (shared remote resource) | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), with an undocumented assumption | High (external system) | High | Yes, tolerable — see note below |
| `drift/*DriftGroup` → `GitHubClient` (typed facade) | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | High (cross-package + external system) | High | Yes — low strength offsets high distance |
| `PklTypes`: `Drifty.*` enums → `client` enums | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | Medium | Medium | Yes — this is the pattern to replicate |
| `state/DriftyState` ↔ secret groups (virtual threads) | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | Low (same process, guarded by `ConcurrentHashMap`) | Low — supporting subdomain | Yes |
| `testsupport/*Args` + `ToDrifty` → `Drifty.*` | [Model](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), duplicated (1,930 LOC shadow schema) | Medium (`main` ↔ `test` source roots) | High | No, but consequence of Issue 5 — not a separate defect |

**Note on the shared `PATCH` endpoint.** Eight groups (`RepoSettings`, `Archived`, and six security toggles) each build their own `RepositoryUpdateRequest` and write the same GitHub resource. This looked like a clobbering hazard, so it was checked: both `RepositoryUpdateRequest` and `SecurityAndAnalysis` carry `@JsonInclude(NON_NULL)`, so each request transmits only its own field and GitHub merges them. The coupling is real but currently benign — its cost is eight sequential round-trips where one would do, and an assumption about GitHub's merge semantics that appears nowhere in the code. Per the [balance rule](https://coupling.dev/posts/core-concepts/balance/) this does not warrant restructuring; a one-line comment recording the assumption would.

## Issue: Fix accounting is keyed on rendered display strings

**Integration**: `drift/*DriftGroup` → `OrgChecker.applyFixes`
**Severity**: Critical

### Knowledge Leakage

The [integration contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) between the drift groups and the orchestrator is `DriftItem::message` — a string built for human eyes. `OrgChecker.checkOne` flattens every group's items into a `List<String>`, and `applyFixes` then removes the ones it believes were fixed with `remaining.removeAll(fixedMsgs)`.

What leaks is identity. A `DriftItem` carries a `path` and typed `wanted`/`got` values, but all of that is discarded at the boundary and the presentation format becomes the join key. The groups have no idea their rendered output is load-bearing, and nothing in the code says so — this is [functional coupling in its implicit form](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), and specifically [connascence of value](https://coupling.dev/posts/related-topics/connascence/) mediated through a display string.

The keys are not unique. Thirteen groups call `compare("enabled", desired, actual)` — `AdvancedSecurity`, `AutomatedSecurityFixes`, `CodeScanningDefaultSetup`, `ImmutableReleases`, `PrivateVulnerabilityReporting`, `SecretScanning`, `SecretScanningAiDetection`, `SecretScanningDelegatedAlertDismissal`, `SecretScanningDelegatedBypass`, `SecretScanningNonProviderPatterns`, `SecretScanningPushProtection`, `SecretScanningValidityChecks`, `VulnerabilityAlerts` — each producing exactly `enabled: want=true got=false`. The group's own `name()` never reaches the message.

### Complexity Impact

This is [complexity](https://coupling.dev/posts/core-concepts/complexity/) in the Cynefin sense: the outcome of a fix run cannot be predicted from the code, only observed. Running the real classes confirms it:

```
vulnerability_alerts message: "enabled: want=true got=false"
immutable_releases   message: "enabled: want=true got=false"

diffs before fix (2): [enabled: want=true got=false, enabled: want=true got=false]
remaining after fix (0): []

immutable_releases fix THREW, yet remaining is EMPTY -> repo reported [OK]
```

`ArrayList.removeAll` removes *every* element equal to the argument, so the first successful fix deletes all thirteen identical lines. Two consequences follow. In fix mode, a group whose fix throws is silently reported as fixed — `applyFixes` catches `RuntimeException` with a bare `continue`, and the item it should have left behind has already been removed by a sibling. The repo prints `[OK]` and drifty exits 0. In check mode, the report prints `enabled: want=true got=false` up to thirteen times with nothing to distinguish which setting is which.

The second half of the same thin contract is that `FixResult` can only say *whether* an item was fixed, never *why not*. `SPEC.md` promises "diffs are replaced with per-setting fix results (FIXED or FAILED with reason)" and "Report all failures at the end". Neither is implemented, and neither can be while the contract carries no failure channel.

The cognitive load is the giveaway: to predict what one drift group's fix will report, a reader must hold the group's message format, the message formats of twelve unrelated groups, `List.removeAll`'s multiset semantics, and the `continue` in the exception handler — well past the 4±1 working-memory budget that [modular design](https://coupling.dev/posts/core-concepts/modularity/) exists to respect.

### Cascading Changes

Every new boolean setting — the confirmed direction of travel — adds a fourteenth, fifteenth colliding key, widening the blast radius at zero visible cost. Changing a message format for readability (adding the setting name, say) silently *fixes* the collision, so a cosmetic edit changes reconciliation behaviour. Conversely, factoring two groups' shared wording into a constant silently *creates* new collisions. Any change to `DriftItem.message()` is a change to fix accounting, and nothing in the type system, tests, or comments records that.

Because the effect only manifests against a live GitHub repo where one fix succeeds and another fails, the distance between cause and observable symptom is maximal: the defect is invisible in unit tests, which exercise groups in isolation.

### Recommended Improvement

Reduce [integration strength](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) by making the contract explicit and identity-bearing, rather than reducing distance (co-locating groups with the orchestrator would be a step backwards).

1. **Stop using strings as identity.** Have `checkOne` keep `List<DriftItem>` and have `applyFixes` subtract by item identity, not by rendered text. Render to strings once, in `printReport`, at the edge where display actually happens.
2. **Make paths unique.** Prefix each group's paths with its `name()` — `vulnerability_alerts.enabled`, `advanced_security.enabled`. Cheap, and it fixes the check-mode report at the same time. Enforce it with a test asserting that the union of all paths produced by `createDriftGroups` has no duplicates; that test is what stops setting fourteen from reintroducing the bug.
3. **Widen `FixResult` to carry failure reasons.** Replace the bare `continue` with a recorded failure, so the `--fix` output and the end-of-run summary that `SPEC.md` specifies become implementable.

**Trade-off**: steps 1 and 3 change signatures that every drift-group test touches, so this is a mechanical edit across ~24 test files. That is real work, and it buys back the guarantee that a `[OK]` line means the repository is actually reconciled — which is the tool's entire value proposition. Step 2 alone is a few hours and removes the demonstrated defect; steps 1 and 3 prevent its return in a different form.

## Issue: GitHub REST response types are the domain model

**Integration**: `client/*Response` → `RepositoryState` → all 24 drift groups
**Severity**: Critical

### Knowledge Leakage

`RepositoryState` is a 19-field record made almost entirely of transport types: `RepositorySummaryResponse`, `RepositoryDetailsResponse`, `BranchProtectionResponse`, `RulesetDetailsResponse`, `EnvironmentDetailsResponse`, `PagesResponse`, `WorkflowPermissions`, `Secret`. These flow unchanged into the drift groups, which then navigate GitHub's wire shape directly — `RulesetDriftGroup` walks `got.conditions().refName().include()`, builds a `Map<RulesetRuleType, Rule>` by pattern-matching on GitHub's rule union, and reaches into `rsc.parameters().requiredStatusChecks()`.

The shared knowledge is therefore not "what a ruleset means to drifty" but "how GitHub's REST API happens to serialise a ruleset in version 2026-03-10". That is [model coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) to a representation drifty does not own and cannot version.

The asymmetry is striking, and it is the strongest evidence that the fix is known to the project already: on the *desired* side, `PklTypes` is a textbook [anti-corruption layer](https://coupling.dev/posts/related-topics/domain-driven-design/) translating every Pkl enum into a client enum, with a class comment explaining exactly why. On the *actual* side there is no equivalent — the wire format goes straight through.

### Complexity Impact

`client/` is a [generic subdomain](https://coupling.dev/posts/dimensions-of-coupling/volatility/): talking to GitHub's REST API is a solved problem with no competitive advantage. The model is explicit that generic subdomains have low *functional* volatility but that *implementation* volatility must be assessed separately — and here it is high and confirmed, because GraphQL bulk reads are planned. This is exactly the case where the model prescribes a strong integration contract to encapsulate provider-specific knowledge.

Without one, "how do we read repository state" is not a decision localised in `client/` and `OrgChecker.fetchState()`; it is a fact known by all 24 drift groups. A reader assessing the cost of the GraphQL migration cannot answer from the client package alone — they must read every group to find out which wire fields are touched.

### Cascading Changes

- **GraphQL bulk reads.** GraphQL returns a different shape from REST. Because comparison logic is written against REST records, a GraphQL path means either rebuilding the REST DTOs from GraphQL responses (keeping the coupling, paying an ongoing translation tax) or editing all 24 groups. Neither is a `client/`-local change — which is the definition of unbalanced coupling at high distance.
- **GitHub API version bumps.** The version is pinned in one place (`X-GitHub-Api-Version: 2026-03-10`) but the *consequences* of moving it are spread across every group that navigates a response.
- **Read-strategy changes generally.** `fetchState()` fetches everything for every repo regardless of what config declares, with N+1 patterns in `fetchRulesets` and `fetchBranchProtections`. Making fetching conditional on the desired config is a natural optimisation, and it is currently an `OrgChecker` change that risks handing groups a partially-populated `RepositoryState` with no type-level signal.

### Recommended Improvement

Reduce strength by introducing the missing half of a translation layer the project already has on the other side.

1. **Give the actual side drifty-owned types**, mirroring what `PklTypes` does for the desired side. `RepositoryState` becomes a record of drifty's own vocabulary rather than of GitHub's serialisation, with translation from `*Response` records happening once, at the `client/` boundary.
2. **Start where the leakage is deepest and most volatile**: `RulesetDriftGroup` (663 LOC) and `BranchProtectionDriftGroup` (415 LOC) hold most of the wire-shape navigation between them. Converting those two captures the large majority of the exposure. The boolean security groups already receive plain `boolean`s and need nothing.
3. **Do not decompose further.** The client is already a well-formed facade at the right distance; the problem is that it hands out its internals, not where it sits.

**Trade-off**: this adds a translation layer and a second set of types to keep current, which is real duplication and will feel like ceremony while REST remains the only read path. It is worth doing before the GraphQL work rather than during it: done first, the migration is contained in `client/`; done during, it is a change to every drift group made under the pressure of a working feature. If GraphQL were dropped from the roadmap, the honest assessment would be that low implementation volatility neutralises this imbalance and it could be left alone.

## Issue: Repository owner is declared in config, ignored, and hardcoded

**Integration**: `config/drifty.pkl` → `GitHubCheck` → `OrgChecker` → 24 drift group constructors
**Severity**: Significant

### Knowledge Leakage

The identity of the account being managed exists in three places that do not agree:

- `config/drifty.pkl` declares `owner: String` as a required field on every `Repository`, and `config/ArloL.pkl` sets it.
- `GitHubCheck.java:72` ignores that and passes a literal: `new OrgChecker(token, "ArloL", fix, githubSecrets, state)`.
- `OrgChecker` holds a single `org` field, calls `client.listOrgRepos(org)` once, and threads `org` into all 24 group constructors, each of which stores its own `owner` copy.

`Drifty.Repository.owner` is never read by production code. This is [duplicated functional knowledge](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) in its most dangerous form — the same fact stated twice, with only one copy actually consulted. `SPEC.md` describes the config field as the mechanism ("The target org or personal account is set via the `owner` field on each repository in the config file. There is no CLI argument for it"), so the specification and the implementation disagree.

### Complexity Impact

Today the effect is contained because there is exactly one owner and the literal happens to match. The cognitive cost is a reader's false confidence: `config/ArloL.pkl` looks like it controls targeting, and editing `owner` there produces no effect and no error. A repo listed under a different owner would be silently looked up under `ArloL` and reported `MISSING`.

### Cascading Changes

Multi-org support is confirmed as planned, and it is the change this coupling makes expensive. It currently requires touching: `GitHubCheck` (drop the literal), `OrgChecker`'s `org` field and all eight of its constructors, `check()`'s single `listOrgRepos` call and its `desiredByName` map (repository names are only unique *within* an owner — `Collectors.toMap(r -> r.name, ...)` will throw on a duplicate name across two orgs), and the constructor of every one of the 24 drift groups, because each caches `owner` independently.

The last part is the coupling cost proper: owner is not passed *through* the system, it is *copied into* 24 places, so widening it from one value to many is a 24-file edit rather than a one-type edit.

### Recommended Improvement

1. **Delete the literal and read the config.** Group `repositories` by `owner` in `check()` and iterate owners; key `desiredByName` on `(owner, name)`. This alone makes `SPEC.md` true and is worth doing independently of multi-org.
2. **Replace the loose `String owner, String repo` pair with a single `RepoRef(String owner, String name)` value object.** Twenty-four constructors currently take two positionally-adjacent strings of the same type — an argument-order swap compiles cleanly and fails at runtime. One parameter of a distinct type removes both the duplication and that hazard, and future multi-org work then changes one type, not 24 signatures.

**Trade-off**: step 2 touches every drift group and its tests — mechanical but broad. Doing it while there is one owner is considerably cheaper than doing it as part of the multi-org feature, and it converts multi-org from a cross-cutting change into a localised one.

## Issue: Fix ordering is an implicit contract across three methods

**Integration**: `createDriftGroups` → `computeGroupDrifts` → `applyFixes`
**Severity**: Significant

### Knowledge Leakage

`ArchivedDriftGroup` must run before every other fix, because GitHub rejects writes to an archived repository. The rule is recorded only in a comment:

> Always first: when actual.archived=true, unarchive must run before any other fix (other fixes fail on archived repos).

Three independent implementation choices must hold for it to be true: `createDriftGroups` returns a `List` with the archive group first; `computeGroupDrifts` accumulates into a `LinkedHashMap` (insertion-ordered); and `applyFixes` iterates `groupDrifts.values()` sequentially. Each is a reasonable local decision, and none of the three sites states that another depends on it. This is [connascence of execution order](https://coupling.dev/posts/related-topics/connascence/) — implicit [functional coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) spread across three methods.

### Complexity Impact

The failure mode is silent and remote. Switching `computeGroupDrifts` to a `HashMap` — an edit that looks like a trivial cleanup and passes every existing test — breaks reconciliation for archived repositories only, only in `--fix` mode, only against a live repo. Combined with Issue 1, the resulting failures would not even be reported.

### Cascading Changes

`check()` already fans out per repository on virtual threads, so parallelising fixes *within* a repository is the obvious next optimisation, and it is precisely what silently violates this invariant. Any future group with its own ordering requirement (a setting that must be enabled before another can be configured — plausible as the security surface grows) has nowhere to express it except by adding another comment and hoping.

### Recommended Improvement

Make the ordering a property of the type rather than of three call sites. Either:

- add an ordering hint to `DriftGroup` (a `priority()` or a `runsFirst()` predicate) that `computeGroupDrifts` sorts on, so the requirement lives next to the group that has it; or
- treat unarchiving as an explicit **phase** in `applyFixes` — run the archive group, then the rest — which states the dependency in the orchestration code where a reader looking to parallelise will actually see it.

The second is simpler and expresses the real constraint (there is one prerequisite, not a general priority scheme). Add a test that asserts fixes on an archived repo unarchive first.

**Trade-off**: a phase split makes `applyFixes` slightly less uniform. That is the point — the uniformity is currently a lie that a reader has to discover from a comment.

## Issue: Whole config aggregate passed to groups that read one field

**Integration**: `pkl/Drifty.Repository` → 21 of 24 drift groups
**Severity**: Minor

### Knowledge Leakage

Twenty-one of the 24 groups take the entire `Drifty.Repository` aggregate. Eleven of those read exactly one field: `VulnerabilityAlertsDriftGroup` uses only `desired.vulnerabilityAlerts`, `AdvancedSecurityDriftGroup` only `desired.advancedSecurity`, and so on. Each such group depends on the shape of the whole desired-state model to consume one boolean — [model coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) where [contract coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) would suffice.

The codebase already contains the counter-example: `TopicsDriftGroup` takes `List<String> desired` and `ArchivedDriftGroup` takes `boolean desiredArchived`. Both are narrower, simpler, and testable without constructing anything.

### Complexity Impact

Low, and this is deliberately ranked Minor. The Pkl type is generated and grows additively, so new fields do not break consumers, and the maintainer reports that adding settings has been cheap. The real cost is displaced rather than absent: because a group cannot be exercised without a full `Drifty.Repository`, the test suite carries `testsupport/` — 1,930 LOC of hand-written `*Args` builders plus `ToDrifty`, a second expression of the Pkl schema that must be extended field-by-field alongside it. That shadow model is a *consequence* of this coupling, not an independent defect.

### Cascading Changes

Adding a setting means: `drifty.pkl`, a `DriftGroup`, `GitHubClient`, request/response records, a `RepositoryArgs` record component, a `ToDrifty` mapping line, and two test files. Nothing there is hard, which is why it has not hurt — but the `testsupport` half of that list exists only because groups take the aggregate.

### Recommended Improvement

Narrow the constructors opportunistically rather than as a campaign. When a boolean group is next touched, change it to take the `boolean` it actually reads, as `TopicsDriftGroup` already does — `OrgChecker.createDriftGroups` is the single call site, so each conversion is a two-line change. Once a group takes primitives, its test constructs primitives, and the corresponding `RepositoryArgs` surface stops being needed.

**Trade-off**: doing this wholesale is churn with no functional payoff, and the current design is not causing pain. Doing it incrementally costs nothing and steadily shrinks the shadow model. If the aggregate is retained deliberately — it does keep `createDriftGroups` uniform — that is a defensible choice; the observation to keep is that `testsupport`'s size is the price of it, so the two decisions should be made together.

## Issue: One rule, three encodings, two dead fields

**Integration**: `OrgChecker.fetchSecurityFlags` / `OrgChecker.securityFlag` / `OrgChecker.createDriftGroups`
**Severity**: Minor

### Knowledge Leakage

"Is this security flag enabled" — meaning `securityAndAnalysis != null && x != null && x.status() == ENABLED` — is written three times in one class:

- `isEnabled(StatusObject)`, used by `fetchSecurityFlags` to populate `RepositoryState`;
- `securityFlag(RepositoryState, Function<...>)`, used for four of the groups;
- inline null-chains at lines 562 and 581, used for `SecretScanningDriftGroup` and `SecretScanningPushProtectionDriftGroup`.

The third encoding has already caused drift within the file: because those two groups re-derive the value inline, `RepositoryState.secretScanning` and `RepositoryState.secretScanningPushProtection` are computed by `fetchSecurityFlags` and then **never read** — zero references each, while the sibling fields `secretScanningNonProviderPatterns` and `secretScanningValidityChecks` have one apiece. Two fields of the state record are dead, and the inconsistency is invisible without grepping.

### Complexity Impact

Modest, because [distance is low](https://coupling.dev/posts/dimensions-of-coupling/distance/) — all three encodings live in one file, so a change is cheap to make in all of them. High strength at low distance is [high cohesion](https://coupling.dev/posts/core-concepts/balance/), which the model treats as balanced. The problem is not cost of change but *consistency* of change: three copies can be updated to disagree, and one already has diverged into disuse.

### Cascading Changes

If GitHub changes how a flag's absence is represented (a new status value, or `security_and_analysis` omitted for some repo type), the handling must land in three places. The two dead fields make it plausible that a reader fixes `fetchSecurityFlags`, sees the state field populated correctly, and never notices the two groups reading a stale inline expression.

### Recommended Improvement

Collapse to one encoding: have `createDriftGroups` pass `actual.secretScanning()` and `actual.secretScanningPushProtection()` — the fields already populated for exactly this purpose — instead of the inline chains, and express `securityFlag` in terms of `isEnabled`. The four remaining `securityFlag(...)` call sites read flags not carried on `RepositoryState`; either add them to `SecurityFlags` for uniformity, or leave them, but do not keep a third form.

**Trade-off**: essentially none. This is a localised cleanup with no interface change, and it makes the two dead fields either live or removable.

## What Is Working

Worth recording, because these are the patterns the recommendations above ask to extend rather than replace:

- **`PklTypes` is a proper anti-corruption layer** between the config model and the client enums, with a class comment explaining its purpose. Issue 2 is essentially "do this on the other side too".
- **`GitHubClient` is a real facade**: typed request/response records, one method per endpoint, rate limiting handled centrally. Low strength against the highest available distance — [balanced](https://coupling.dev/posts/core-concepts/balance/).
- **`DriftFix` / `FixResult` is the right *shape* of contract** — detection returns a value that carries its own remediation, so groups compose without the orchestrator knowing what they do. Issue 1 is about what that contract carries, not its design.
- **Concurrency is handled correctly**: per-repo fan-out on virtual threads, with `DriftyState` guarded by `ConcurrentHashMap` and a synchronised `hash()`.
- **The state file is well-scoped** — it stores the minimum that cannot be recovered from the API (secret `updated_at` plus a salted hash), is versioned, and is only written in `--fix` mode, exactly as specified.

---

_This analysis was performed using the [Balanced Coupling](https://coupling.dev) model by [Vlad Khononov](https://vladikk.com)._
