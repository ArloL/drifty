# Partial management: per-repo managed drift groups

Issue: [#85 Ignore settings](https://github.com/ArloL/drifty/issues/85)

A repo can declare which drift groups drifty manages, in one of two modes:
manage everything except a named set, or manage only a named set. The default
is "everything except nothing" — every repo behaves exactly as it does today.

## Why groups rather than fields

Group-level control is nearly field-level control already. Of the 23 drift
groups, 21 cover a single setting or one coherent object — `secret_scanning`,
`vulnerability_alerts`, `immutable_releases`, `pages`, `action_secrets`,
`rulesets`. Only `repo_settings` (20 fields) and `workflow_permissions` (2)
bundle settings a user might want to split.

Field-level control would instead require every field in `config/drifty.pkl` to
become nullable, because the schema has no way to distinguish a field left alone
from a field explicitly set to GitHub's default: `hasWiki: Boolean = true` reads
the same either way. That change touches all 23 groups, every generated type,
the "Field Defaults" section of `SPEC.md`, and all 42 repo entries in
`config/ArloL.pkl`.

If `repo_settings` granularity turns out to matter, splitting that group is a
smaller change than making the whole schema nullable.

## Schema

`config/drifty.pkl` gains a group-name typealias listing all 23 names, a
`Managed` class, and a `managed` field on `Repository`:

```pkl
typealias GroupName = "action_secrets" | "advanced_security" | "archived" | ...

typealias ManageMode = "all_except" | "only"

class Managed {
  mode: ManageMode = "all_except"
  groups: Listing<GroupName> = new {}
}

class Repository {
  // ...
  managed: Managed = new {}
}
```

Pkl rejects a group name that is not in the union at config-eval time. A typo
that silently disabled management would be the dangerous failure for this
feature, so the union is what prevents it, not a runtime check.

Usage:

```pkl
// strict, minus the groups someone else owns
(foreignOrgRepo) { managed { mode = "all_except"; groups { "action_secrets"; "rulesets" } } }

// partial: manage only these
(foreignOrgRepo) { managed { mode = "only"; groups { "repo_settings"; "topics" } } }
```

## Group identity

`DriftGroup.name()` changes from `String` to the generated `Drifty.GroupName`.
Pkl codegen emits enums that carry their source string and return it from
`toString()`, so `Drifty.GroupName.ACTION_SECRETS.toString()` is the
`"action_secrets"` that drift paths and the `Would fix:` line already print.
Report output does not change.

The Pkl schema is then the single source of truth for group names, and the Java
compiler checks every use. No mirror table and no test asserting two lists match.

## Skipping fetches, not just diffs

`fetchState` must skip an unmanaged group's requests. A repo in a foreign org is
the case this feature exists for, and there some of these calls return 403
before any group runs — reporting the error would defeat the point of declaring
the group unmanaged.

`fetchState` already skips requests for archived repos and substitutes empty
values. Unmanaged groups follow that path. `checkOne` has `desired` before it
calls `fetchState`, so the managed set is available there.

| Group | Requests skipped when unmanaged | Substitute |
|---|---|---|
| `action_secrets` | `getActionSecrets` | empty list |
| `environment_config`, `environment_secrets` | `getEnvironments`, `getEnvironmentSecrets` | empty maps |
| `rulesets` | `listRulesets`, `getRuleset` per ruleset | empty list |
| `branch_protection` | `getBranches`, `getBranchProtection` per branch | empty map |
| `pages` | `getPages` | `Optional.empty()` |
| `workflow_permissions` | `getWorkflowPermissions` | not applicable — see below |
| `vulnerability_alerts` | `getVulnerabilityAlerts` | `false` |
| `automated_security_fixes` | `getAutomatedSecurityFixes` | `false` |
| `immutable_releases` | `getImmutableReleases` | `false` |
| `private_vulnerability_reporting` | `getPrivateVulnerabilityReporting` | `false` |
| `code_scanning_default_setup` | `getCodeScanningDefaultSetup` | `false` |
| the nine secret-scanning and `advanced_security` groups | none — all read from the `getRepo` response | not applicable |
| `repo_settings`, `topics`, `archived` | none — `getRepo` and the summary are always needed | not applicable |

`workflow_permissions` has no natural empty value: `RepositoryState` holds the
response type, not an `Optional`. Make the field nullable and let the skip store
null, since no group reads it once the group is filtered out.

## Filtering in one place

`createDriftGroups` builds the full list, then drops unmanaged groups in a
single statement before returning. Filtering at each `groups.add` call would put
the requirement in 23 places, and a new group added later would silently ignore
the mode.

The archived short-circuit — which returns only `ArchivedDriftGroup` — passes
through the same filter. An `only` set that omits `archived` therefore leaves an
archived-state mismatch unreported, which is what declaring the group unmanaged
asks for.

## Reporting what is unmanaged

`CheckResult.RepoCheckResult` gains an unmanaged-group list, printed for `OK`
and `DRIFT` repos alike:

```
repo-a: OK
  Unmanaged: action_secrets, rulesets
```

The line names groups, not their actual values. Fetching actual values for
unmanaged groups would undo the 403 avoidance above.

## Org-level rulesets

`GitHubClient.listRulesets` requests `/rulesets?per_page=100`. GitHub's
`includes_parents` parameter defaults to true, so org-level rulesets appear in
that list. `RulesetSourceType` is parsed into `RulesetSummaryResponse` and
`RulesetDetailsResponse` and read nowhere, so `RulesetDriftGroup` reports every
org ruleset as `extra (should not exist)`, and `--fix` calls
`DELETE /repos/{owner}/{repo}/rulesets/{id}` on a ruleset that endpoint cannot
delete.

`fetchRulesets` drops rulesets whose `sourceType` is `ORGANIZATION`. This is a
bug fix independent of the mode feature and lands as its own commit.

## Testing

- Pkl eval rejects an unknown group name.
- `Only` and `AllExcept` each produce the expected group set from
  `createDriftGroups`, including the archived short-circuit path.
- A WireMock scenario where an unmanaged group's endpoint returns 403: the check
  passes and never requests it.
- `fetchRulesets` drops an `Organization`-sourced ruleset from a listing that
  mixes both source types.
- Report output carries the unmanaged line.

## Out of scope

Field-level nullability, for the migration cost above.

Existence-without-value for secrets — declaring that a secret must exist while
leaving its value to someone else. Excluding `action_secrets` solves the
motivating case; the weaker claim keeps the check that catches a deleted secret
and is worth adding once partial management is in place. It is a separate
claim rather than a mode, so it does not fit the group-name mechanism.
