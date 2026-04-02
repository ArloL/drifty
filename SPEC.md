# drifty Specification

## Overview

**drifty** is a Java CLI tool that manages the configuration of GitHub repositories for a single org or personal account. It compares actual repository state against desired configuration defined in Java code, reports drift, and can automatically fix discrepancies via `--fix`.

## Core Concepts

### Configuration Model

Desired repository state is defined **in Java code** using builder-style APIs. There is no external config file format — the tool IS the config.

#### Field Defaults

`RepositoryArgs` field defaults match **GitHub's defaults** for newly created repos. This means a bare `RepositoryArgs.create("my-repo").build()` represents a repo with GitHub's out-of-the-box settings and reports no drift against a freshly created repo.

Non-default desired values (e.g. disabling merge commits, enabling auto-merge) are set in the `defaultRepository` template in `GitHubCheck.repositories()`, not in `RepositoryArgs` itself.

#### Grouping Model

Repos are organized into groups that share defaults. Each group defines baseline settings via a template `RepositoryArgs`, and individual repos can override any field via `toBuilder()`.

```java
// GitHubCheck.repositories() — pseudocode showing the grouping model
var defaultRepository = RepositoryArgs.create("default")
    .allowAutoMerge(true)
    .allowMergeCommit(false)
    .deleteBranchOnMerge(true)
    .secretScanning(true)
    // ... org-wide policy overrides
    .build();

var repos = List.of(
    defaultRepository.toBuilder().name("repo-a").description("...").build(),
    defaultRepository.toBuilder().name("repo-b").description("...").topics("library", "java").build(),
    // Per-repo overrides
    defaultRepository.toBuilder().name("special-repo").allowSquashMerge(true).build()
);
```

### Org/Account Targeting

The target org or personal account is **hardcoded in the config code**. There is no CLI argument for it. To manage multiple orgs, run the tool multiple times with different config blocks. Multi-org support within a single invocation is a future consideration.

### Archived Repos

Repos marked `archived=true` in config are only checked for being archived. All other settings are skipped.

If a repo is configured as `archived=true` but is currently active, `--fix` will archive it.

### Missing Repos

If a repo is listed in config but does not exist on GitHub, it is reported as `MISSING` and causes a non-zero exit code. drifty does not create repos — it only manages settings of existing repos.

## CLI Interface

### Commands

```
drifty          # Report drift with human-readable diffs and fix previews
drifty --fix    # Apply all fixable changes
```

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DRIFTY_GITHUB_TOKEN` | Yes | GitHub personal access token with repo, admin:org, workflow scopes |
| `GITHUB_SECRETS` | No | JSON map of secret values (required for secret creation via `--fix`) |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | No drift detected |
| 1 | Drift detected, or errors occurred during fix |

### Output

**Default (no `--fix`):** Compact field-level diffs per repo, plus human-readable previews of what `--fix` would do. All repos are listed, including those with no drift.

```
repo-a: OK
repo-b: DRIFT
  description: "old value" -> "new value"
  allowAutoMerge: false -> true
  Would fix: update description, enable auto-merge
repo-c: UNKNOWN (not in config)
repo-d: MISSING (in config, not on GitHub)
repo-e: ERROR: 403 Forbidden
```

**With `--fix`:** Same output, but diffs are replaced with per-setting fix results (FIXED or FAILED with reason). Failed fixes are also collected in a summary at the end.

## Managed Settings

All settings below are fields on `RepositoryArgs`. Field defaults in `RepositoryArgs` match GitHub's defaults for newly created repos — the "GitHub default" column documents these. Non-default desired values are set in the `defaultRepository` template in `GitHubCheck.repositories()`.

### Repository Settings

| Setting | GitHub default | Check | Fix |
|---------|---------------|-------|-----|
| Description | `""` | Yes | Yes |
| Homepage URL | `""` | Yes | Yes |
| Topics/tags | `[]` | Yes | Yes |
| Visibility (public/private) | `"public"` | Yes | No (too risky — public→private breaks forks, private→public exposes code) |
| Default branch | `"main"` | Yes | Yes |
| Issues enabled | `true` | Yes | Yes |
| Projects enabled | `true` | Yes | Yes |
| Wiki enabled | `true` | Yes | Yes |
| Allow merge commits | `true` | Yes | Yes |
| Allow squash merge | `true` | Yes | Yes |
| Allow rebase merge | `true` | Yes | Yes |
| Allow auto-merge | `false` | Yes | Yes |
| Allow update branch | `false` | Yes | Yes |
| Delete branch on merge | `false` | Yes | Yes |
| Archived | `false` | Yes | Yes (can archive active repos) |

### Security Settings

All configurable per-repo via `RepositoryArgs`, with defaults matching GitHub's defaults. drifty manages every security setting exposed by the GitHub REST API:

| Setting | GitHub default (public repos) | Check | Fix |
|---------|-------------------------------|-------|-----|
| Vulnerability alerts (Dependabot alerts) | enabled | Yes | Yes |
| Automated security fixes (Dependabot security updates) | disabled | Yes | Yes |
| Secret scanning | enabled | Yes | Yes |
| Secret scanning push protection | enabled | Yes | Yes |
| Secret scanning validity checks | disabled | Yes | Yes |
| Secret scanning non-provider patterns | disabled | Yes | Yes |
| Private vulnerability reporting | disabled | Yes | Yes |
| Code scanning default setup | disabled | Yes | Yes |

### Workflow Settings

| Setting | GitHub default | Check | Fix |
|---------|---------------|-------|-----|
| Default workflow permissions (read/write) | `"write"` | Yes | Yes |
| Can approve pull request reviews | `true` | Yes | Yes |

### Branch Protection (Legacy)

Managed via an explicit `branchProtectionArgs` field on `RepositoryArgs`. If the field is set (non-null), legacy branch protection is managed for that repo. If null, legacy protection is not managed (regardless of whether rulesets are configured). A repo can have both legacy protection and rulesets.

| Setting | Check | Fix |
|---------|-------|-----|
| Enforce admins | Yes | Yes |
| Required linear history | Yes | Yes |
| Allow force pushes | Yes | Yes |
| Required status checks | Yes | Yes |
| Required pull request reviews | Yes | Yes |
| Restrictions (users, teams, apps) | Yes | Yes |

#### Required Pull Request Reviews

Full configuration of pull request review requirements:

| Sub-setting | Check | Fix |
|-------------|-------|-----|
| Required approving review count | Yes | Yes |
| Dismiss stale reviews | Yes | Yes |
| Require code owner reviews | Yes | Yes |
| Restrict dismissals (users/teams) | Yes | Yes |
| Require last push approval | Yes | Yes |

#### Restrictions

Full configuration of push restrictions:

| Sub-setting | Check | Fix |
|-------------|-------|-----|
| Users | Yes | Yes |
| Teams | Yes | Yes |
| Apps | Yes | Yes |

### Repository Rulesets

Repo-level rulesets managed via the `rulesets` list on `RepositoryArgs`. drifty supports all GitHub ruleset rule types:

| Setting | Check | Fix |
|---------|-------|-----|
| Ruleset name and enforcement | Yes | Yes |
| Target branch/tag patterns | Yes | Yes |
| Bypass actors (roles, teams, apps) | Yes | Yes |
| Creation | Yes | Yes |
| Update | Yes | Yes |
| Deletion | Yes | Yes |
| Required signatures | Yes | Yes |
| Required linear history | Yes | Yes |
| Non-fast-forward (force push) | Yes | Yes |
| Required status checks | Yes | Yes |
| Pull request requirements | Yes | Yes |
| Commit message pattern | Yes | Yes |
| Commit author email pattern | Yes | Yes |
| Committer email pattern | Yes | Yes |
| Branch name pattern | Yes | Yes |
| Tag name pattern | Yes | Yes |
| Required deployments | Yes | Yes |
| Required code scanning | Yes | Yes |

**Extra rulesets:** Rulesets that exist on the repo but are not in config are reported as drift. `--fix` deletes them.

### Required Status Checks

Defined as a base set per group plus per-repo additions:

```java
var defaultRepository = RepositoryArgs.create("default")
    .requiredStatusChecks("CodeQL", "codeql-analysis", "zizmor")
    .build();

defaultRepository.toBuilder().name("my-repo").addRequiredStatusChecks("build", "test").build()
// Results in: CodeQL, codeql-analysis, zizmor, build, test
```

### GitHub Pages

Full lifecycle management of Pages configuration (enable and disable):

| Setting | Check | Fix |
|---------|-------|-----|
| Pages enabled | Yes | Yes (enable/disable) |
| Build type (workflow/legacy) | Yes | Yes |
| Source branch and path | Yes | Yes |
| HTTPS enforced | Yes | Yes |

If config has no Pages and the repo has Pages enabled, `--fix` disables it.

### Action Secrets

Config declares expected secret names per repo:

```java
defaults.toBuilder().name("my-repo").secrets("PAT", "DOCKER_HUB_ACCESS_TOKEN").build()
```

**Check:** Verifies that the declared secret names exist on the repo.

**Fix:** `--fix` always creates/updates secrets when `GITHUB_SECRETS` provides a value (no staleness detection). If the value is not provided, reports the drift as unfixable.

#### Secret Value Mapping

The `GITHUB_SECRETS` env var contains a JSON map. Keys are formed by concatenating repo name, optional environment name, and secret name with hyphens:

```json
{
  "my-repo-PAT": "ghp_xxxx",
  "my-repo-production-TF_GITHUB_TOKEN": "ghp_yyyy"
}
```

- Repo action secret: `<repo>-<secret_name>`
- Environment secret: `<repo>-<environment>-<secret_name>`

### Environments

Create and update environments. Extra environments (on GitHub but not in config) are reported as drift but not deleted by `--fix`.

| Setting | Check | Fix |
|---------|-------|-----|
| Environment exists | Yes | Yes (create) |
| Environment secrets | Yes | Yes (via `GITHUB_SECRETS`) |
| Required reviewers | Yes | Yes |
| Wait timer | Yes | Yes |
| Deployment branch policies | Yes | Yes |

### Immutable Releases

Per-repo setting:

| Setting | Check | Fix |
|---------|-------|-----|
| Enabled | Yes | Yes |

## Unmanaged Repos

Repos that exist in the GitHub org but are not listed in config are reported as `UNKNOWN` with a warning and cause a non-zero exit code.

## Error Handling

### Fix Failures

When `--fix` encounters an error (API failure, insufficient permissions, missing secret value):

1. Log the failure for that specific setting/repo
2. Continue fixing everything else
3. Report all failures at the end
4. Exit with code 1

The tool never fails fast — it always attempts all fixes and provides a complete report.

## Technical Architecture

### Language & Build

- **Language:** Java 25
- **Build:** Maven with Spring Boot parent POM (for dependency management, not Spring framework features)
- **Distribution:** Run via `mvn exec:java`
- **Parallelism:** Virtual threads for concurrent repo checks/fixes

### API Strategy

REST API only. Both reads and writes use the GitHub REST API v3. GraphQL for bulk reads is a future consideration.

### Rate Limiting

Monitor `X-RateLimit-Remaining` header and sleep until reset when exhausted. No additional concurrency control.

### Authentication

Bearer token via `DRIFTY_GITHUB_TOKEN` environment variable. The token needs sufficient scopes for all managed settings (repo, admin:org, workflow).

### Testing Strategy

- **Unit tests:** WireMock-based HTTP mocking for all API interactions
- **Recording/playback:** Use WireMock's recording mode to capture real API responses and replay them in CI
- **No live test org required for CI**

## CI Integration

The tool is run **on-demand** (e.g. via `workflow_dispatch`). No scheduled cron or PR-triggered checks.

## Future Considerations

These are explicitly out of scope for the initial version but acknowledged as potential additions:

- **Org-level rulesets** — manage rulesets at the org level (full CRUD, same as repo-level). Repo-level first.
- **Multi-org support** — manage multiple orgs in a single invocation with per-org config blocks.
- **GraphQL for bulk reads** — REST first, profile and optimize later.
- **Lightweight state file** — track secret `updated_at` timestamps to skip unchanged secrets on subsequent runs.
- **Collaborator/team access management** — out of scope for now, may be added later.
- **Custom properties** — manage GitHub custom property values per repo (org-level definitions assumed to exist).
- **Webhooks** — full lifecycle management of repo webhooks (URL, events, content type, secrets via `GITHUB_SECRETS`).
- **Repository lifecycle** — create/delete/transfer repos is out of scope. drifty only manages settings of existing repos plus archival.
