# Exporting current state as Pkl

`drifty --export <login>` writes a Pkl config describing what an account looks
like on GitHub right now, so a developer can read the file and see the lay of
the settings without clicking through GitHub's UI — and so adopting drifty for
an existing organization starts from a file rather than a blank page.

The file lists only what differs from the schema's defaults. Everything absent
is at GitHub's default, which is the same rule the checker already works by:
`config/drifty.pkl`'s defaults are GitHub's defaults, so an account nobody has
touched exports to an empty entry.

## What the file looks like

```pkl
/// Exported by drifty 1.4.2 from acme on 2026-09-07T09:14:03Z.
/// Only settings that differ from the schema defaults are listed.
amends "https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl"

organizations {
  ["acme"] {
    displayName = "Acme Inc"
    defaultRepositoryPermission = "write"
    membersCanForkPrivateRepositories = true
    // two_factor_requirement_enabled is true on GitHub; drifty reports this
    // setting but PATCH /orgs/{org} does not accept it

    teams {
      ["platform"] {
        description = "Platform engineering"
        members { "arlol"; "dependabot" }
      }
    }

    rulesets {
      ["main"] {
        includePatterns { "refs/heads/main" }
        requiredLinearHistory = true
        pullRequest { requiredApprovingReviewCount = 1 }
      }
      // "enterprise-baseline" is owned by the enterprise; drifty does not
      // manage it
    }

    // codeSecurityConfigurations: HTTP 403 reading
    // /orgs/acme/code-security/configurations

    repositories {
      new {
        name = "api"
        allowMergeCommit = false
        deleteBranchOnMerge = true
        actionsSecrets { "NPM_TOKEN" }
        // secret values are never returned by GitHub; supply them through
        // DRIFTY_GITHUB_SECRETS
      }
      new {
        name = "docs"
        archived = true
      }
    }
  }
}
```

A personal account exports the same way under `users`, which has nothing but
`repositories`.

## Command line

```
drifty --export <login> [<login> ...]
       [--out <path>]     # default ./export.pkl
       [--schema <uri>]   # default the raw.githubusercontent.com main URL
```

`DRIFTY_GITHUB_TOKEN` is required, as for a check run. `--export` combined with
`--config` or `--fix` is a usage error: an export reads no config and writes
nothing to GitHub.

`export.pkl` is a dedicated output name rather than drifty's default input
`drifty.pkl`, so an existing config is never at risk and the file is simply
overwritten. Several logins export into one file, each as its own
`organizations` or `users` entry.

`GET /orgs/{login}` decides which kind of account this is; a 404 means a
personal account. Drifty then requires that the login match
`GET /user`, because `/user/repos` — the only listing that returns a personal
account's private and archived repositories — serves the authenticated user
alone. Exporting somebody else's personal account fails with that reason
instead of silently emitting the token owner's repositories under their name.

Exit code 0 when every login exported, 1 when one failed outright. Gaps inside
a file are comments, not failures.

## Where the state comes from

The exporter reads `OrganizationChecker.fetchState` and
`RepositoryChecker.fetchState` with `ManagedGroups.all(...)`, the same calls the
checker makes. There is no second read path, so everything `ActualTypes`
normalises — ids resolved to names, `{"status": "enabled"}` unwrapped, nulls
that mean `""` — is already done, and the `Actual*` records are already named
the way the schema is: `ActualOrganization.displayName`,
`ActualRuleset.includePatterns`.

### Per-group failures become notes, not a dead entry

Today a 403 on any group's request propagates out of `fetchState` and the whole
account or repository is reported ERROR. An export has to survive that: a token
without `admin:org` should still produce a file, with a note where the
unreadable group would have been.

Both `fetchState` methods gain a `FetchFailures` collaborator. The default,
`FetchFailures.STRICT`, rethrows, so check and fix behave exactly as they do
now. Export passes a collecting one, and each group's block becomes

```java
List<ActualTeam> teams = read(ORG_TEAMS, () -> teams(login), List.of());
```

so a failure records `(group, message)` and yields the empty value. This is the
only change to existing production code.

## The node tree and the writer

New package `io.github.arlol.githubcheck.export`.

`PklNode` is a sealed interface — `Scalar`, `Obj`, `Mapping`, `Listing`, and
`Note` (a `//` line, valid anywhere a member is). `PklWriter` is the only class
that knows Pkl syntax: indentation, `=` versus `{`, string escaping, `new {}`
for a listing element, `["key"] { … }` for a mapping entry.

Each section has an exporter that emits nodes, one explicit line per field:

```java
field("allowMergeCommit", actual.allowMergeCommit(), defaults.allowMergeCommit)
```

`field` yields a member when the values differ and nothing when they match, so
the default comparison lives in one helper rather than in every exporter.
`mapping` and `listing` are omitted when empty, since empty is their default.

**A field with no schema default is always emitted.** `Repository.name`,
`Webhook.url`, `CustomProperty.valueType`, `StatusCheck.context`,
`BypassActor.actorId` and the rest have no default to differ from, and a
config missing one fails to evaluate. `Fields.required(name, value)` is the
call that says so.

The exporters mirror the drift groups: `OrganizationExporter`,
`RepositoryExporter`, `RulesetExporter` (shared by both scopes, the way
`RulesetComparison` is), `BranchProtectionExporter`, `EnvironmentExporter`,
`WebhookExporter`, `TeamExporter`, `CustomPropertyExporter`,
`CodeSecurityConfigurationExporter`, `RunnerGroupExporter`, `PagesExporter`,
`SecretExporter`, `VariableExporter`. `DriftyFileExporter` assembles the
module: header comment, `amends` line, `organizations` and `users` blocks.

No `managed` block is ever emitted — an export manages everything.

## Defaults come from the schema

`SchemaDefaults` is the production twin of `testsupport.Desired`: it evaluates
the schema once and hands out `Drifty.Organization`, `Drifty.Repository`,
`Drifty.Ruleset` and the rest carrying the schema's defaults. A field added to
`config/drifty.pkl` needs no change here.

It evaluates the same URI the exported file amends, so what the export diffed
against is exactly what the file resolves to. That costs one network fetch of
the schema per run — Pkl caches it — and it is what keeps the two from
disagreeing. `--schema` points both at a local path, which is what the tests
use.

## Notes: what the file says about what it cannot say

Four kinds of `Note`, each a single line where the entry would have gone:

| Kind | Source | Example |
|---|---|---|
| Unreadable group | `FetchFailures` | `// codeSecurityConfigurations: HTTP 403 reading /orgs/acme/code-security/configurations` |
| Enterprise-owned entity | dropped in the checker | `// "enterprise-baseline" is owned by the enterprise; drifty does not manage it` |
| Secret value | always | `// secret values are never returned by GitHub; supply them through DRIFTY_GITHUB_SECRETS` |
| Reported but not writable | the `Setting` tables | `// two_factor_requirement_enabled is true on GitHub; drifty reports this setting but PATCH /orgs/{org} does not accept it` |

Exception messages are trimmed to their first line: `GitHubApiException`
carries the response body, and a JSON blob does not belong in a config file.

The last kind is the one that makes the file honest about `visibility` and the
ten check-only organization settings. Emitting them as ordinary fields would
promise a `--fix` that cannot happen; omitting them silently would hide a real
difference from the reader.

## What does not round-trip

- **Secret values.** Names and, for org secrets, visibility and selected
  repositories are exported. The value comes from `DRIFTY_GITHUB_SECRETS`.
- **Webhook secrets.** `secret = true` records that a hook has one.
- **Archived repositories.** The checker does not fetch security flags for one,
  so the export emits `archived = true` and a note rather than guessing.
- **Code security sub-objects.** `codeScanningDefaultSetupOptions` and
  `secretScanningDelegatedBypassOptions` are emitted only when GitHub returned
  them, with a note that setting them means drifty now compares those fields —
  null in the schema means unmanaged, and a defaulted object would report drift
  on every configuration created with only a name.

## Testing

- `PklWriterTest` — syntax, escaping, nesting, notes in each position.
- One test per exporter: a `Desired.*` default plus an `Actual*` fixture,
  asserting exactly the drifted fields appear.
- **Acceptance, and the reason the design holds together:** export a state,
  load the file back through the real `PklConfigLoader`, run the checker
  against a WireMock serving that same state, assert zero drift.
- **Schema coverage guard**, test scope only, so it needs no production
  native-image metadata: for a fixture where every field is off default, every
  public field of `Drifty.Organization`, `Drifty.Repository` and
  `Drifty.Ruleset` must appear in the output. A schema field with no exporter
  line fails the build — the export's counterpart to
  `DriftPathNamespacingTest`.
- `--self-test` renders a canned state, so `PklWriter` and the exporters are
  exercised in the shipped image the way `selfTestWithConfig` covers Pkl
  evaluation.

## Files

New, in `io.github.arlol.githubcheck.export`: `PklNode`, `PklWriter`,
`Fields`, `SchemaDefaults`, `DriftyFileExporter`, and the per-section
exporters. `ExportRunner` and `FetchFailures` sit beside `GitHubCheck` in the
root package — the runner because `fetchState`'s package-private visibility
reaches it there, `FetchFailures` because the two checkers are what hold one.

Changed: `GitHubCheck` (argument handling, the `--export` branch, `--self-test`
render), `OrganizationChecker` and `RepositoryChecker` (`FetchFailures`),
`SPEC.md`, `README.md`, `FEATURES.md`, `CLAUDE.md`. `GitHubClient` needs
nothing: `getAuthenticatedUser` already exists.

## Out of scope

Reading an existing config and updating it in place; exporting anything drifty
does not manage; `--export` against a config's account list. The file is
generated fresh each time and is a starting point, not a merge target.
