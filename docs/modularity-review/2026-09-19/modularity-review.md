# Modularity Review

**Scope**: Entire codebase — `src/main/java/io/github/arlol/githubcheck` (CLI, two checkers, 27 repository and 12 organization drift groups, the GitHub REST/GraphQL client, the Pkl exporter, the state store) plus `config/drifty.pkl`
**Date**: 2026-09-19

## Executive Summary

drifty reads a GitHub account's real settings, compares them against a desired state declared in Pkl, reports the difference, and with `--fix` writes it away. Since the [previous review](../2026-08-30/modularity-review.md) the three findings it called Critical and Significant have all been resolved — fix accounting is keyed on `DriftItem` identity rather than rendered strings, the `actual/*` package is now a real [anti-corruption layer](https://coupling.dev/posts/related-topics/domain-driven-design/) on the read side to match `PklTypes` on the write side, `RepoRef` carries owner identity, and `DriftGroup.runsBeforeOtherFixes()` states the unarchive-first rule where the group that needs it lives. The design is in good health and the remaining imbalances are narrower.

**The most important finding is that two records in the `actual` package carried strings whose spelling was `Enum.toString()` of a client enum, and the exporter wrote those strings into the config file verbatim.** Exporting any ruleset with a bypass actor produced `actorType = "ORGANIZATION_ADMIN"` and `bypassMode = "PULL_REQUEST"`, where `config/drifty.pkl` declares `"Integration" | "OrganizationAdmin" | "RepositoryRole" | "Team"` and `"always" | "pull_request"`. The exported file did not evaluate. Both sides were tested and both tests passed, because each constructed its own half of the vocabulary. The defect is now fixed; the arrangement that produced it — one spelling stated independently in several places, with nothing checking the copies agree — is not, and is now stated in seven places rather than five.

Below that, two pieces of hard-won reconciliation logic exist as verbatim copies — the settings-table PATCH machinery (73 identical lines in `RepoSettingsDriftGroup` and `OrgSettingsDriftGroup`, already diverged in their prose) and the check-to-report pipeline in the two checkers (already diverged in which exceptions it catches). Both are places where the two copies must agree and nothing makes them.

**What this branch changed.** Issues 2 to 5 are fixed here. Issue 1 was fixed in parallel by "Read and export every kind of ruleset bypass actor", which is already on `main`; it is described below as found, with a note on what that fix settled and what it did not.

## Coupling Overview Table

Level of abstraction analysed: **packages and classes within one deployable**. Per the model's [fractal geometry](https://coupling.dev/posts/core-concepts/balance/), the highest distance available here is the cross-package boundary; GitHub's API is a separate system, and the `main` ↔ `test` source-root boundary sits between the two. There is one maintainer, so [socio-technical distance](https://coupling.dev/posts/dimensions-of-coupling/distance/) adds nothing.

| Integration | [Strength](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | [Distance](https://coupling.dev/posts/dimensions-of-coupling/distance/) | [Volatility](https://coupling.dev/posts/dimensions-of-coupling/volatility/) | [Balanced?](https://coupling.dev/posts/core-concepts/balance/) |
| --- | --- | --- | --- | --- |
| Enum spelling as a bare `String` through `ActualTypes` → `actual/*` → the exporters | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), implicit — one rule, seven independent statements of it | High (three packages; only a Pkl evaluation of the output would notice) | High — rulesets are the most-edited part of the schema | **No — Critical** (the defect is fixed; the shape is not) |
| `RepoSettingsDriftGroup` ↔ `OrgSettingsDriftGroup` (the `Setting` table and the 422 re-send rule) | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), duplicated | Low (same package) but both copies must agree | High — a managed setting is added most weeks | **No — Significant** |
| `RepositoryChecker.entry` ↔ `OrganizationChecker.check` (state → `CheckResult.Entry`) | [Functional](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), duplicated | Low (same package) but both copies must agree | High — `fixPreview`, `unmanaged` and `fixReports` were each added to both | **No — Significant** |
| `ExportRunner` → `RepositoryChecker` / `OrganizationChecker` (uses the read half, constructs the whole) | [Model](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) — depends on the checker's shape to reach `fetchState` | Medium (same package only because `fetchState` is package-private) | High — every read change lands here | **No — Significant** |
| `client/*` REST read methods the GraphQL query replaced | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), unused | High (cross-package) | Low — dead code does not change | **No — Minor** |
| `drift/*DriftGroup` → `GitHubClient` (typed facade) | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | High (cross-package + external system) | High | Yes — low strength offsets high distance |
| `drift/*` and `export/*` → `actual/*` records | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) — drifty's own vocabulary, guarded by `ActualStateBoundaryTest` | High (cross-package) | High | Yes — this is the pattern the Critical issue breaks |
| `PklTypes`: `Drifty.*` enums → `client` enums | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | Medium | Medium | Yes |
| `GitHubClient` transport core ↔ its ~130 endpoint methods (one 3,104-line class) | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) — a four-method seam (`get`, `sendRequest`, `readValue`, `writeValue`) | Low (same class) | High on both halves | Tolerable — see note |
| `DriftGroup` → `DriftFixer` (`DriftItem` identity, `runsBeforeOtherFixes`) | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | Medium | High | Yes — the previous review's Critical finding, now fixed |
| `state/DriftyState` ↔ secret groups and the response cache | [Contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) | Low (same process, `ConcurrentHashMap`) | Low — supporting subdomain | Yes |

**Note on `GitHubClient`'s size.** Transport policy (semaphore, `RequestPacer`, `ResponseCache`, rate-limit retries, `Link`-header pagination, JSON mapping) and an endpoint catalogue of ~130 typed methods share one 3,104-line class, meeting only at `get`/`sendRequest`/`readValue`/`writeValue`. Low strength at the lowest possible distance is [low cohesion](https://coupling.dev/posts/core-concepts/balance/), and the cost is real: changing pagination means scrolling past 130 endpoints. But nothing outside the class depends on the arrangement, so no change cascades — the class's *external* coupling is [contract coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) at high distance, which is balanced. The [balance rule](https://coupling.dev/posts/core-concepts/balance/) does not call for restructuring here, and a 780-line move would be churn against a class the recent performance work has been editing continuously. Recorded, not recommended.

## Issue: An enum's spelling is decided by `Enum.toString()` and consumed as a config literal

**Integration**: `ActualTypes.bypassActors` → `actual/ActualRuleset.BypassActor` → `export/RulesetExporter` and `drift/RulesetComparison`
**Severity**: Critical
**Status**: the defect is fixed on `main`; see *What the fix settled* below

### Knowledge Leakage

`ActualRuleset.BypassActor` declares its two enum-valued fields as `String`:

```java
public record BypassActor(String actorType, Long actorId, String bypassMode)
```

`ActualTypes` fills them with `String.valueOf(a.actorType())` and `String.valueOf(a.bypassMode())` — the *Java constant name* of a `client` enum. That is a third vocabulary, distinct from both the one GitHub uses on the wire (`OrganizationAdmin`, declared in the enum's `@JsonProperty`) and the one `config/drifty.pkl` declares (`"OrganizationAdmin"`, `"pull_request"`).

Two components then read that field with incompatible expectations, and neither says so:

- `RulesetComparison.compareBypassActors` builds the wanted side as `PklTypes.actorType(a.actorType) + ":" + a.actorId + ":" + PklTypes.bypassMode(a.bypassMode)`, which is also `Enum.toString()` of a client enum. It needs the Java constant name, and gets it.
- `RulesetExporter.bypassActor` writes `required("actorType", actor.actorType())` straight into the Pkl file. It needs the config spelling, and gets the Java constant name.

This is [functional coupling in its implicit form](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/): the rule "how an enum is spelled" is shared knowledge, and it is shared by convention through an untyped `String` rather than through a contract. `AccountExporter` records the hazard in a class comment and defends against it with four hand-written `wire()` switches, `RepositoryExporter` with a fifth, and `ActualTypes` with a private `wire(Enum<?>)` that lower-cases the name — four independent encodings of one rule, none of which reaches `bypassActors`. The lower-casing rule is itself false for 13 of the 111 `@JsonProperty`-annotated client enum constants, among them every `ActorType`.

### Complexity Impact

This is [complexity](https://coupling.dev/posts/core-concepts/complexity/) in the Cynefin sense: the outcome is only visible by running the export and then evaluating its output. Running it confirms the defect:

```
bypassActors {
  new {
    actorId = 5
    actorType = "ORGANIZATION_ADMIN"
    bypassMode = "PULL_REQUEST"
  }
}
```

against a schema that declares `typealias ActorType = "Integration" | "OrganizationAdmin" | "RepositoryRole" | "Team"` and `typealias BypassMode = "always" | "pull_request"`. The file `--export` writes does not evaluate, so the adopter's very first `drifty` run fails on the file drifty just produced — the same class of failure as issue #136, arriving through a different door.

The test suite cannot see it. `ActualTypesTest.readsBypassActors` asserts `"TEAM:5:ALWAYS"`, which is what the read path produces. `RulesetExporterTest.workflowsAndBypassActorsAreFullyRequiredFields` hand-constructs `new ActualRuleset.BypassActor("Team", 7L, "always")` and asserts the exporter writes `"Team"`, which is what the render path does with the value the test gave it. Each test agrees with itself; nothing runs the two halves against each other, and `ExportRoundTripTest` declares no bypass actor.

The cognitive load is the giveaway. To predict what one exported field contains, a reader must hold: the client enum's constant name, its `@JsonProperty` value, the Pkl union's literal, which of five `wire()` functions applies, and the fact that this particular field reaches the exporter through none of them — past the 4±1 working-memory budget [modular design](https://coupling.dev/posts/core-concepts/modularity/) exists to respect.

### Cascading Changes

- **Any new enum-valued field on an `actual/*` record.** The author must independently rediscover which of the three vocabularies the consumer needs. Nothing in the type system distinguishes a `String` holding a wire value from one holding a Java constant name.
- **A GitHub rename.** GitHub changing `OrganizationAdmin` means editing the `@JsonProperty`; the comparison path keeps working (both sides use the constant name) while the export path stays wrong in a new way. The two paths fail independently, which is why one has been wrong without the other noticing.
- **A schema vocabulary change.** Editing a `typealias` in `config/drifty.pkl` regenerates `Drifty.java` and breaks `PklTypes`' exhaustive switch at compile time — but nothing connects it to the `String` the exporter writes.

### Recommended Improvement

Reduce [strength](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) by making the spelling explicit and single-sourced, rather than reducing distance.

1. **Give `actual/*` one vocabulary and say which.** These records are drifty's own read model — `ActualStateBoundaryTest` already enforces that they hold no client response type. Extend that intent: an enum-valued field carries the **config spelling**, because the config is the only vocabulary both consumers can be held to. `ActualTypes` becomes the one translator, which it already is for every other field.
2. **Derive the spelling from the `@JsonProperty` annotation instead of restating it.** One translation function replaces `ActualTypes.wire`'s lower-casing rule, the four `wire()` switches in `AccountExporter` and the fifth in `RepositoryExporter` — and cannot disagree with what Jackson actually sends, because it reads the same annotation.
3. **Pin the invariant with a test.** Assert that every `@JsonProperty`-annotated client enum constant appearing in an exported field spells the value `config/drifty.pkl`'s union declares. That test is what stops the next enum-valued field from reintroducing this.
4. **Fix the comparison side to match**, so `RulesetComparison` builds its wanted set from the same vocabulary rather than from `Enum.toString()`.

**Trade-off**: reading the annotation costs a small amount of reflection, which the native image needs metadata for — the project already maintains that metadata and the types are its own. The alternative, keeping hand-written switches but adding the guard test, is cheaper but leaves five tables to keep current. Single-sourcing is worth the reflection because the failure mode is a file that does not parse, discovered by the adopter rather than by CI.

### What the fix settled

The fix landed while this review was being written. It fixed the defect and found three more the analysis above missed, because it went to a live ruleset rather than to the code: the client enum knew four of GitHub's six actor types and two of its three bypass modes, so a ruleset letting an individual user bypass it failed the whole repository read, and the GraphQL path put an `actorId` of 1 on an `OrganizationAdmin` actor that REST never returns. It took recommendation 1 — `actual/*` now carries the config spelling, and `RulesetComparison` was moved to match — and step 4. It made the schema stronger than proposed: `actorId` is not merely nullable but constrained to be null for exactly the two id-less types.

It did not take recommendations 2 and 3, and the residual imbalance is worth naming. The spelling is still restated by hand — two more `wire()` switches were added beside the five that existed, and `ActualTypes.wire(Enum<?>)`'s lower-casing rule still sits next to them while being false for 13 of the 111 `@JsonProperty`-annotated client enum constants. What has changed is the blast radius: an exhaustive switch now forces an arm for a new actor type, and `ExportRoundTripTest` carries all six types and all three modes through a real Pkl evaluation, so the remaining hole is narrow — an arm added with a misspelled string, for a type nobody adds to that fixture. Recommendations 2 and 3 close it; nothing else does.

## Issue: The 422 re-send rule is written twice

**Integration**: `drift/RepoSettingsDriftGroup` ↔ `drift/OrgSettingsDriftGroup`
**Severity**: Significant

### Knowledge Leakage

Both groups hold a private `Setting` record pairing a comparison with the builder call that writes it, and both implement `fix` / `write` / `writeIndividually` / `request` on top of it. The `Setting` record is byte-identical once the builder type is renamed; the 73-line tail differs only in the two `client.update*` calls, the builder class, and — already — the wording of the shared javadoc, where one copy says "reporting the whole request as failed *told* the operator the opposite of what had happened" and the other says "*would tell*".

What is duplicated is not boilerplate but a business rule, and one the project treats as hard-won: *GitHub applies the fields it accepts and rejects the rest, so a 422 attributes to no field; re-send each field alone and report only the ones that fail again.* CLAUDE.md records it as an invariant with the failure it was written to prevent. Holding it in two places is [duplicated functional coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) — the extreme case the model names explicitly, where a specification change not reflected in both copies simultaneously leaves the system inconsistent.

### Complexity Impact

[Distance is low](https://coupling.dev/posts/dimensions-of-coupling/distance/) — same package, adjacent files — so a change is cheap to make in both. The problem is not cost but *consistency*: two copies can be updated to disagree, and the prose already has. A reader who fixes an attribution bug in one file has no signal that the other exists; the only thing connecting them is that they look alike.

The rule is also subtle enough that a partial fix looks correct. `writeIndividually` re-sending a field that the batch already applied is deliberate (it simply repeats), and the `writable.size() == 1` short-circuit is deliberate (a single-field request is its own attribution). Someone reconstructing that reasoning for a third settings group — or correcting one copy — has to rediscover both.

### Cascading Changes

- **A third PATCH-shaped group.** Teams, environments and code security configurations each already batch several fields into one write; any of them adopting per-field attribution means a third copy.
- **Changing what a rejection reports.** Adding the HTTP status to the reason, or capping the individual re-sends, is a two-file edit that nothing enforces.
- **A new check-only setting.** Both tables carry `Setting.checkOnly` with a group-specific reason constant; the mechanism is duplicated even though the reasons rightly differ.

### Recommended Improvement

Reduce strength by extracting the shared rule into one [integration contract](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/), keeping the per-scope tables where they are.

1. **Extract a generic `SettingTable<B>`** into the `drift` package holding `Setting<B>` (path, wanted, got, `Consumer<B> write`, `unfixableReason`) and the whole `fix` / `write` / `writeIndividually` / `request` sequence, parameterised by a `Supplier<B>` for the builder and a `Consumer<B>` for the send. Each group keeps only its own rows and its own reason constant — the part that genuinely differs.
2. **Write the javadoc once**, on the extracted type, so the reasoning lives next to the code it explains rather than in two copies that have already drifted apart.
3. **Keep `OrgSettingsDriftGroupTest.everyWritableSettingWritesTheFieldItCompared`** reading its cases out of the table, and give `RepoSettingsDriftGroup` the same test — the extraction makes that a shared helper rather than a second copy.

**Trade-off**: a generic type over a builder is slightly more indirection than two concrete copies, and a reader of `RepoSettingsDriftGroup` now has one more hop to make to see what `fix` does. That is the right trade: the hop leads to a single statement of a rule that currently exists twice, and the rows — the part a maintainer actually edits when adding a setting — stay exactly where they are.

## Issue: The check-to-report pipeline is written twice

**Integration**: `RepositoryChecker.entry` / `computeGroupDrifts` ↔ `OrganizationChecker.check` / `computeGroupDrifts`
**Severity**: Significant

### Knowledge Leakage

Turning a set of drift groups into a `CheckResult.Entry` is one rule with five steps: name the unmanaged groups from the config's own block; run `detect()` and keep only groups that reported a drifted item; in fix mode run `DriftFixer.applyFixes` and build `Entry.fixed` from `render` plus `fixReports`; otherwise flatten every item to its message; and return `Entry.ok` or `Entry.drift` with the `Would fix:` preview. It is implemented twice, once per checker, with `computeGroupDrifts` duplicated verbatim.

Nothing in the type system says the two must agree. They are two statements of one rule, connected only by looking alike — [implicit functional coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) in its duplicated form. The copies are not identical, and the one real difference is instructive: `RepositoryChecker.entry` catches `IOException` and `InterruptedException` as well as `GitHubApiException`, while `OrganizationChecker.check` catches only the last. That one turns out to be correct rather than drifted — `RepositoryStateReader.fetchState` declares the two checked exceptions and the organization's does not, because `Fanout` converts an interrupt into a `GitHubApiException` before it gets out — but establishing that takes reading three files, which is exactly the cognitive cost the duplication imposes. A reader who assumed the copies should match would "fix" the organization side and add an unreachable arm.

### Complexity Impact

Every addition to the report contract has had to be made twice, and the history shows it: the `unmanaged` list, the `fixPreview` that issue #156 added, and the `fixReports` that made SPEC.md's per-setting `FIXED`/`FAILED` output real were each applied to both files. Three for three is not luck to rely on — it is the count of times a single-copy rule has been re-entered by hand.

The failure mode when it is missed is quiet. A `Would fix:` preview added only to repositories does not fail any test; it produces an organization report that is subtly less informative than the repository report beside it, and only a reader comparing the two would notice.

### Cascading Changes

- **Any change to what an entry carries.** SPEC.md's end-of-run failure summary, a machine-readable output mode, or a per-group timing line each mean two edits.
- **Changing how group drifts are computed.** `computeGroupDrifts`'s "keep a group only when it reported a drifted item" rule exists twice; it was introduced to stop the preview naming groups that had not drifted, and the organization copy carries a javadoc pointing at the repository copy for the explanation — the code already admits the duplication.
- **A third scope.** Enterprise-level settings are named in SPEC.md as out of scope today; if that changes, the natural move is a third checker and a third copy.

### Recommended Improvement

Reduce strength by giving the rule one home. It is already generic over the group-name enum — `DriftGroup<N>`, `ManagedGroups<N>` and `DriftFixer` all are — so this needs no new abstraction, only a place to put it.

1. **Extract the pipeline into the `drift` package**, beside `DriftFixer`, which already produces `CheckResult` types and already serves both scopes: `entry(name, ManagedGroups<N>, groups, fix)` returning the `CheckResult.Entry`. Both checkers call it.
2. **Move `computeGroupDrifts` with it**, since it is the same method twice and its explanation is already written once with a cross-reference.
3. **Leave failure handling at the call sites.** What each checker has to catch is decided by its own fetch, not by the report, so the shared version should not take it over — and with the pipeline gone from around them, the two `catch` blocks stop looking like a discrepancy and start reading as the two different fetches they belong to.

**Trade-off**: the checkers lose a little self-containment — reading `RepositoryChecker.checkOne` no longer shows the whole path to the report. In exchange, the path is stated once, and the failure handling that genuinely differs stays visible at each call site instead of hiding among five steps that do not.

## Issue: The export depends on the checkers to use only their read half

**Integration**: `ExportRunner` → `RepositoryChecker` / `OrganizationChecker`
**Severity**: Significant

### Knowledge Leakage

Reading an entity's actual state and comparing it against a config are two jobs, and each checker does both. The export needs only the first, and says so at every call site:

```java
var repositoryChecker = new RepositoryChecker(
        client, false, Map.of(), new DriftyState(), FetchFailures.collecting());
```

Three of the five arguments are inert. `fix` is false because nothing is fixed, `githubSecrets` is empty because no secret is written, `state` is fresh because no baseline is recorded — three values chosen to neutralise a half of the class the export never enters. The one thing it wants, `fetchState`, is package-private, so `ExportRunner` cannot leave the root package to get at it, and neither can `Fanout`, `FetchFailures`, `RepositoryState` or `OrganizationState`.

The leakage is that the export depends on the *shape of the checker* — its constructor arity, its field set, which of its methods are visible — rather than on a contract for reading state. That is [model coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) where [contract coupling](https://coupling.dev/posts/dimensions-of-coupling/integration-strength/) would serve, and `ExportRunner`'s own javadoc spends a paragraph explaining which parts of `RepositoryChecker` it is and is not using.

### Complexity Impact

The class names mislead. `ExportRunner` constructs a "checker" per repository and never checks; `RepositoryChecker` carries five constructors, three of which exist only so tests can omit arguments, one of which builds a `GitHubClient` from a token it is handed as `(String) null`. A reader working out what an export does must first work out which half of a checker is live.

It also blurs a real invariant. `FetchFailures.STRICT` versus `collecting()` is the whole difference between "a 403 ends this entry" and "a 403 becomes a note in the file" — a decision that belongs to the reader of state, not to the comparer. Today it is a constructor argument on a class that does both, so nothing says which half it governs.

### Cascading Changes

- **Any change to the read path.** The GraphQL rewrite, the response cache, the two-level `Fanout` and the `ARCHIVED_ONLY` narrowing each touched `RepositoryChecker`, and each had to be re-checked against an export path that enters the class by a different door.
- **Fanning the export out.** The export reads repositories strictly one at a time while the check fans them out; making them match means either duplicating `check`'s executor logic in `ExportRunner` or widening the checker further.
- **A fourth consumer.** A dry-run or diff-against-file mode would face the same choice: construct a checker it does not want, or copy `fetchState`.

### Recommended Improvement

Reduce strength by splitting the class along the seam that already exists, without increasing distance.

1. **Extract `RepositoryStateReader` and `OrganizationStateReader`** into the same package, each taking exactly `(GitHubClient, FetchFailures)` and exposing `fetchState` and `fetchFailures`. The checkers hold one; `ExportRunner` constructs one with the two things it actually needs.
2. **Let the extraction delete the inert arguments** rather than merely hiding them, so a reader of `ExportRunner` sees a reader being built, not a checker being neutralised.
3. **Drop the test-only checker constructors** the split makes unnecessary, including the one that manufactures a client from a null token.

**Trade-off**: two more classes, and the checkers gain a delegation hop. The hop is worth it because the seam is not invented — it is already where `ExportRunner`'s javadoc, `FetchFailures`' two implementations and the `fix` flag all fall — and naming it is what lets the export path stop describing, in prose, which half of a class it is allowed to touch.

## Issue: The REST read path the GraphQL query replaced is still public API

**Integration**: `client/GitHubClient` → its callers
**Severity**: Minor

### Knowledge Leakage

`GitHubClient.graphqlRepository` replaced six REST reads with one query, and the six methods are still there: `getVulnerabilityAlerts`, `getBranchProtection`, `getCollaborators`, `getRuleset`, `listRulesets` and, for a different reason, `getAutomatedSecurityFixes` — which was dropped when the same bit turned out to be on the repository details response. No production code calls any of them; all but `getCollaborators` are still exercised by client tests.

Nothing depends on them, so no change cascades — this is not unbalanced coupling but [low cohesion](https://coupling.dev/posts/core-concepts/balance/): a module offering capabilities nobody uses. What leaks is the *answer to "how does drifty read a ruleset"*, which now has two plausible answers in the same class and no marker saying which is live.

### Complexity Impact

Modest, and ranked Minor for that reason. The cost is a reader's time and a misleading signal from the suite: `GitHubClientPlaybackTest` and `GitHubClientRecordingTest` maintain fixtures for endpoints the binary never calls, so a passing client suite says less about the shipped read path than its size suggests. CLAUDE.md's claim that the query "replaced" six requests is true of the request count and not of the code.

### Cascading Changes

Few, which is the point — but not zero. Each dead method is a `client` record and a test fixture that a GitHub API version bump invites someone to update, and a plausible mistake for a future group is to call one of them and quietly reintroduce the per-repository request the query exists to avoid.

### Recommended Improvement

Delete them, and repoint the tests that used them as a convenient GET onto a method the binary still calls — `getRepo` or `getPrivateVulnerabilityReporting` — so the rate-limit, pacing and concurrency tests keep testing what they were testing.

**Trade-off**: the REST fallback goes away, so a token that may not use GraphQL has no other route. That is already true in practice: nothing selects between the two paths, and restoring a fallback would mean building the selection as well as the method. Deleting states the current design honestly; keeping it states a choice that was never implemented.

## What Is Working

Worth recording, because the recommendations above ask to extend these rather than replace them:

- **Every finding of the previous review has been addressed, and the fixes match what was recommended.** Fix accounting is by `DriftItem` identity; `DriftGroup.detect()` namespaces paths under the group name so two groups cannot collide; `FixResult.Unfixed` carries reasons and `SPEC.md`'s FIXED/FAILED output is real; `ActualTypes` and the `actual/*` package are the anti-corruption layer the read side lacked; `RepoRef` replaced the loose owner/name pair; `DriftGroup.runsBeforeOtherFixes()` states the unarchive-first rule where the group that needs it lives.
- **`ActualStateBoundaryTest` and `DriftPathNamespacingTest` are structural guards, not sample-based ones.** They pin the invariant rather than an example of it, which is why the Critical issue above is about the one field that escapes them rather than about the boundary in general.
- **`ManagedGroups` is consulted at all three points it has to be** — group construction, request sending, and secret collection — with a class comment explaining why skipping any one of them is wrong.
- **`Fanout` and `FetchFailures` are small, generic over the group-name enum, and independently testable**, and `RepositoryCheckerRequestShapeTest` and `OrganizationCheckerRequestShapeTest` fail on a fan-out that grows a level.
- **The performance reasoning is written down where the code is**, with measurements, in CLAUDE.md and `docs/performance/where-a-checks-time-goes.md`, including the dead ends — which is what keeps a future reader from re-trying them.

---

_This analysis was performed using the [Balanced Coupling](https://coupling.dev) model by [Vlad Khononov](https://vladikk.com)._
