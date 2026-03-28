# drifty Specification

## Overview

**drifty** is a Java CLI tool that manages the configuration of GitHub repositories for a single org or personal account. It compares actual repository state against desired configuration defined in Java code, reports drift, and can automatically fix discrepancies via `--fix`.

## Core Concepts

### Configuration Model

Desired repository state is defined **in Java code** using builder-style APIs. There is no external config file format — the tool IS the config.

Repos are organized into groups that share defaults. Each group defines baseline settings, and individual repos can override any field. All settings are checked for every non-archived repo — there is no opt-out for individual fields.

```java
// Pseudocode showing the grouping model
var defaults = RepositoryArgs.builder()
    .allowAutoMerge(true)
    .deleteBranchOnMerge(true)
    .secretScanning(true)
    // ... all defaults
    .build();

var repos = List.of(
    defaults.toBuilder().name("repo-a").description("...").build(),
    defaults.toBuilder().name("repo-b").description("...").topics("library", "java").build(),
    // Per-repo overrides
    defaults.toBuilder().name("special-repo").allowSquashMerge(true).build()
);
```

### Org/Account Targeting

The tool targets a **single org or personal account per invocation**, specified as a CLI argument:

```
drifty ArloL
drifty ArloL --fix
```

### Archived Repos

Repos marked `archived=true` in config are only checked for being archived. All other settings are skipped.

If a repo is configured as `archived=true` but is currently active, `--fix` will archive it.

## CLI Interface

### Commands

```
drifty <owner>          # Report drift, show compact diffs and the API calls --fix would make
drifty <owner> --fix    # Apply all fixable changes
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

**Default (no `--fix`):** Compact field-level diffs per repo, plus the API calls that `--fix` would make.

```
repo-a: OK
repo-b: DRIFT
  description: "old value" -> "new value"
  allowAutoMerge: false -> true
  Would fix: PATCH /repos/ArloL/repo-b {"allow_auto_merge": true, "description": "new value"}
repo-c: UNKNOWN (not in config)
repo-d: ERROR: 403 Forbidden
```

**With `--fix`:** Same output, but diffs are replaced with fix results (applied/failed).

A `--verbose` flag can increase output detail.

## Managed Settings

### Repository Settings

| Setting | Check | Fix |
|---------|-------|-----|
| Description | Yes | Yes |
| Homepage URL | Yes | Yes |
| Topics/tags | Yes | Yes |
| Visibility (public/private) | Yes | Yes |
| Default branch | Yes | No (too destructive) |
| Issues enabled | Yes | Yes |
| Projects enabled | Yes | Yes |
| Wiki enabled | Yes | Yes |
| Allow merge commits | Yes | Yes |
| Allow squash merge | Yes | Yes |
| Allow rebase merge | Yes | Yes |
| Allow auto-merge | Yes | Yes |
| Delete branch on merge | Yes | Yes |
| Archived | Yes | Yes (can archive active repos) |

### Security Settings

| Setting | Check | Fix |
|---------|-------|-----|
| Vulnerability alerts | Yes | Yes |
| Automated security fixes | Yes | Yes |
| Secret scanning | Yes | Yes |
| Secret scanning push protection | Yes | Yes |

### Workflow Settings

| Setting | Check | Fix |
|---------|-------|-----|
| Default workflow permissions (read/write) | Yes | Yes |
| Can approve pull request reviews | Yes | Yes |

### Branch Protection (Legacy)

Applies to public repos by default. Configurable per repo.

| Setting | Check | Fix |
|---------|-------|-----|
| Enforce admins | Yes | Yes |
| Required linear history | Yes | Yes |
| Allow force pushes | Yes | Yes |
| Required status checks | Yes | Yes |
| Required pull request reviews | Yes | Yes |
| Restrictions | Yes | Yes |

### Repository Rulesets

Repo-level rulesets as a modern alternative to legacy branch protection. Org-level rulesets are a future addition.

| Setting | Check | Fix |
|---------|-------|-----|
| Branch name patterns | Yes | Yes |
| Required status checks | Yes | Yes |
| Required reviews | Yes | Yes |
| Linear history | Yes | Yes |
| Force push restrictions | Yes | Yes |

### Required Status Checks

Defined as a base set per group plus per-repo additions:

```java
var defaults = RepositoryArgs.builder()
    .requiredStatusChecks("CodeQL", "codeql-analysis", "zizmor")
    .build();

defaults.toBuilder().name("my-repo").addRequiredStatusChecks("build", "test").build()
// Results in: CodeQL, codeql-analysis, zizmor, build, test
```

### GitHub Pages

Full management of Pages configuration:

| Setting | Check | Fix |
|---------|-------|-----|
| Pages enabled | Yes | Yes (enable/disable) |
| Build type (workflow/legacy) | Yes | Yes |
| Source branch and path | Yes | Yes |
| HTTPS enforced | Yes | Yes |

### Action Secrets

Config declares expected secret names per repo:

```java
defaults.toBuilder().name("my-repo").secrets("PAT", "DOCKER_HUB_ACCESS_TOKEN").build()
```

**Check:** Verifies that the declared secret names exist on the repo.

**Fix:** If `GITHUB_SECRETS` env var is provided and contains the value, creates/updates the secret using GitHub's encrypted secrets API. If the value is not provided, reports the drift as unfixable.

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

Full environment lifecycle and configuration management:

| Setting | Check | Fix |
|---------|-------|-----|
| Environment exists | Yes | Yes (create) |
| Environment secrets | Yes | Yes (via `GITHUB_SECRETS`) |
| Required reviewers | Yes | Yes |
| Wait timer | Yes | Yes |
| Deployment branch policies | Yes | Yes |

### Immutable Releases

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

- **Phase 1:** REST API only (current approach). Both reads and writes use the GitHub REST API v3.
- **Phase 2 (future):** GraphQL for bulk reads to reduce API call count. REST for all mutations.

### Rate Limiting

Current approach: monitor `X-RateLimit-Remaining` header and sleep until reset when exhausted. No additional concurrency control.

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

- **Org-level rulesets** (repo-level first, org-level later)
- **GraphQL for bulk reads** (REST first, profile and optimize later)
- **Lightweight state** for tracking secrets (to skip unchanged secrets on subsequent runs)
- **Collaborator/team access management** (out of scope, handled elsewhere)
- **Repository lifecycle** (create/delete/transfer — out of scope, tool only manages settings of existing repos plus archival)
