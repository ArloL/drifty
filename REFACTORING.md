# Drift Detection Refactoring Specification

## Problem

`OrgChecker.java` is ~2069 lines containing all drift detection and fix logic.
Detection produces `List<String>` diff messages, then `applyFixes()` re-runs
all 79+ comparisons to decide what to fix. There are 15+ duplicate set
comparisons, 5 identical pattern-rule blocks, and 27 flat field checks that
all follow the same structure.

## Architecture

### Hybrid Model

**DriftItems** are pure data records describing what drifted. **DriftGroups**
are one-shot objects that hold context, produce DriftItems via `detect()`, and
apply fixes via `fix()`. Detection runs once; the orchestrator collects items
for reporting and calls `fix()` on groups that have drift.

```
OrgChecker (orchestrator)
  └─ creates DriftGroups (one per entity)
       ├─ detect() → List<DriftItem>   (pure data, no side effects)
       └─ fix()                         (calls GitHubClient directly)
```

### DriftItem — Sealed Interface Hierarchy

```java
public sealed interface DriftItem {
    String path();     // e.g., "repo_settings.has_issues"
    String message();  // auto-generated from structured data

    record FieldMismatch(String path, Object wanted, Object got) implements DriftItem {
        public String message() {
            return path + ": want=" + wanted + " got=" + got;
        }
    }

    record SetDrift(String path, Set<?> missing, Set<?> extra) implements DriftItem {
        public SetDrift {
            missing = Set.copyOf(missing);
            extra = Set.copyOf(extra);
        }
        public String message() {
            var parts = new ArrayList<String>();
            if (!missing.isEmpty()) parts.add("missing: " + sorted(missing));
            if (!extra.isEmpty())   parts.add("extra: " + sorted(extra));
            return path + " " + String.join(", ", parts);
        }
    }

    record SectionMissing(String path) implements DriftItem {
        public String message() { return path + ": missing"; }
    }

    record SectionExtra(String path) implements DriftItem {
        public String message() { return path + ": extra (should not exist)"; }
    }
}
```

Four variants:

| Variant | Use case | Example |
|---------|----------|---------|
| `FieldMismatch` | Scalar field differs | `has_issues: want=true got=false` |
| `SetDrift` | Set has missing/extra elements | `status_checks missing: [ci, lint]` |
| `SectionMissing` | Expected section absent | `branch_protection.required_pull_request_reviews: missing` |
| `SectionExtra` | Unwanted entity exists | `ruleset[stale-ruleset]: extra (should not exist)` |

Messages are auto-generated from structured data. Output format is free to
change from current strings.

### DriftGroup — Abstract Base Class

```java
public abstract class DriftGroup {

    public abstract String name();          // e.g., "repo_settings", "branch_protection[main]"
    public abstract List<DriftItem> detect();
    public abstract void fix();

    // ─── Protected helpers for subclasses ───

    protected static List<DriftItem> compare(String path, Object wanted, Object got) {
        if (wanted != null && !Objects.equals(wanted, got)) {
            return List.of(new DriftItem.FieldMismatch(path, wanted, got));
        }
        return List.of();
    }

    protected static <T> List<DriftItem> compareSets(String path, Set<T> wanted, Set<T> got) {
        Set<T> missing = new HashSet<>(wanted);
        missing.removeAll(got);
        Set<T> extra = new HashSet<>(got);
        extra.removeAll(wanted);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            return List.of(new DriftItem.SetDrift(path, missing, extra));
        }
        return List.of();
    }
}
```

- Helpers return `List<DriftItem>` — they do not mutate a passed-in list.
  `detect()` implementations collect results with `items.addAll(compare(...))`.
- Groups are constructed with all context (desired state, actual state,
  GitHubClient, owner, repo) — `detect()` and `fix()` are parameterless.
- Groups are disposable one-shot objects.
- Null handling is explicit: each group checks `if (desired.field() != null)`
  before calling `compare()`.

### Group Scope

One group instance per entity:

- `RepoSettingsDriftGroup` — one per repo
- `BranchProtectionDriftGroup("main")` — one per branch
- `RulesetDriftGroup("ci-required")` — one per ruleset
- `VulnerabilityAlertsDriftGroup` — one per repo
- `ActionSecretsDriftGroup("DEPLOY_KEY")` — one per secret

### Security Micro-Groups

Security settings are split into separate groups (one per toggle), not
combined into a single group:

- `VulnerabilityAlertsDriftGroup`
- `AutomatedSecurityFixesDriftGroup`
- `SecretScanningDriftGroup`
- `SecretScanningPushProtectionDriftGroup`
- `PrivateVulnerabilityReportingDriftGroup`
- `DependencyGraphDriftGroup`
- `GhasAdvancedSecurityDriftGroup`
- `GhasSecretScanningValidityChecksDriftGroup`

Each is atomic — one drift check, one API call to fix.

### Secrets

Secrets have two modes:

- **Presence-only**: When the secret value is not known locally, drift is
  detected only if the secret does not exist on GitHub. No fix can be applied
  (value unknown).
- **Always-update**: When the secret value is provided via environment
  variable, the secret is always written (encrypted and pushed) regardless
  of whether it already exists, since values cannot be read back to compare.

### Fix Granularity

Each group pushes the **full desired state** for its category when any drift
is detected. There is no selective per-item fixing. You either fix everything
in a group or nothing.

### Fix Ordering

Hardcoded in the orchestrator:

1. **Unarchive** first (if repo should be unarchived) — required before any
   other changes
2. **All other groups** — no ordering constraints between them
3. **Archive** last (if repo should be archived) — must happen after all
   other changes

### Extra Detection

The factory methods that create groups for a category take both desired and
actual state. They produce groups for:

- **Configured entities** that have drifted from desired state
- **Unwanted entities** that exist but have no desired counterpart (e.g.,
  extra rulesets, extra branch protections)

Unwanted-entity groups produce `SectionExtra` items and their `fix()` method
deletes the entity.

## Package Layout

```
src/main/java/io/github/arlol/githubcheck/drift/
├── DriftItem.java                          # sealed interface + 4 record variants ✓
├── DriftGroup.java                         # abstract base class with helpers ✓
├── TopicsDriftGroup.java                   # ✓ migrated
├── RepoSettingsDriftGroup.java
├── WorkflowPermissionsDriftGroup.java
├── PagesDriftGroup.java
├── VulnerabilityAlertsDriftGroup.java
├── AutomatedSecurityFixesDriftGroup.java
├── SecretScanningDriftGroup.java
├── SecretScanningPushProtectionDriftGroup.java
├── PrivateVulnerabilityReportingDriftGroup.java
├── DependencyGraphDriftGroup.java
├── GhasAdvancedSecurityDriftGroup.java
├── GhasSecretScanningValidityChecksDriftGroup.java
├── EnvironmentConfigDriftGroup.java
├── ActionSecretsDriftGroup.java
├── EnvironmentSecretsDriftGroup.java
├── BranchProtectionDriftGroup.java
└── RulesetDriftGroup.java
```

`OrgChecker.java` shrinks to orchestration only: fetch state, create groups,
collect drift items, report, and optionally fix.

## Orchestrator Flow (OrgChecker)

During migration, `OrgChecker` runs both the legacy path and the group path
in parallel. The current wiring in `checkOne`:

```java
// 1. Fetch actual state (unchanged)
RepositoryState state = fetchState(summary);

// 2. Detect group drift (once — used for both reporting and fixing)
Map<DriftGroup, List<DriftItem>> groupDrifts = computeGroupDrifts(state, desired);

// 3. Legacy detection (unmigrated categories)
List<String> diffs = computeDiffs(state, desired);

// 4. Merge group messages into the legacy diffs list
groupDrifts.values().stream().flatMap(List::stream)
    .map(DriftItem::message).forEach(diffs::add);

// 5. Fix (if --fix) — two separate methods, called in sequence
if (fix) {
    diffs = applyFixes(name, state, desired, diffs);   // legacy
    diffs = applyFixes(name, diffs, groupDrifts);      // group-based
}
```

The target end state (once all categories are migrated) eliminates the legacy
`computeDiffs`/`applyFixes` methods entirely.

## Migration Plan

Incremental migration, one category at a time. Each step:

1. Create the new drift group class(es) in the `drift` package
2. Add unit tests for `detect()`
3. Wire into `OrgChecker` replacing the old check/fix methods for that category
4. Verify with `./mvnw verify`

### Migration Order

| Step | Category | Complexity | Status |
|------|----------|-----------|--------|
| 1 | Topics | Trivial | ✓ done |
| 2 | Repo Settings | Low | ✓ done |
| 3 | Workflow Permissions | Low | 2-3 fields. |
| 4 | Pages | Low | Small group with create-or-update fix logic. |
| 5 | Security micro-groups | Medium | 8 small groups. Proves the micro-group pattern. |
| 6 | Environment Config | Medium | Per-environment groups with reviewer sets. |
| 7 | Secrets | Medium | Two sub-types (action/environment), presence-only vs always-update. |
| 8 | Branch Protection | High | Complex nested structure: PR reviews, restrictions, status checks. |
| 9 | Rulesets | High | Most complex: pattern rules, bypass actors, code scanning tools. |

### During Migration

`OrgChecker` temporarily has two patterns coexisting (old `List<String> diffs`
for unmigrated categories, new `DriftGroup` for migrated ones). This is
acceptable — each migration step is self-contained.

The `OrgCheckerDiffTest` topics tests were removed after step 1 since
`TopicsDriftGroupTest` provides equivalent coverage. Follow this pattern for
each subsequent step: remove the corresponding `OrgCheckerDiffTest` cases and
update `OrgCheckerFixTest` cases to use `computeGroupDrifts` + the group
`applyFixes` overload.

**Important**: When migrating a category, preserve the `OrgCheckerFixTest`
tests rather than removing them. Update them to use the new group-based logic:
call both `applyFixes(..., diffs)` (legacy) and `applyFixes(..., groupDrifts)`
(the group-based overload) in sequence. This ensures the fix path remains
tested even after migration.

## Testing

Unit tests are added with each migration step. Each test:

- Constructs a drift group with known desired and actual state (no GitHub
  client needed for `detect()`)
- Verifies the correct DriftItem variants are produced
- Verifies no items are produced when state matches

```java
@Test
void detectsMissingTopics() {
    var group = new TopicsDriftGroup(
        List.of("java", "cli"),   // desired
        List.of("java"),          // actual
        null, "owner", "repo"     // client not needed for detect()
    );
    var items = group.detect();
    assertThat(items).hasSize(1);
    assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
    var drift = (DriftItem.SetDrift) items.getFirst();
    assertThat(drift.missing()).hasSize(1);
    assertThat(drift.message()).isEqualTo("topics missing: [cli]");
}
```

## Key Files

- `src/main/java/io/github/arlol/githubcheck/OrgChecker.java` — orchestrator (to be slimmed)
- `src/main/java/io/github/arlol/githubcheck/drift/` — drift group implementations
- `src/main/java/io/github/arlol/githubcheck/RepositoryState.java` — actual state record
- `src/main/java/io/github/arlol/githubcheck/config/RepositoryArgs.java` — desired state
- `src/main/java/io/github/arlol/githubcheck/client/GitHubClient.java` — API client (`@Immutable`)
