# drifty Specification

## Overview

**drifty** is a Java CLI tool that manages GitHub configuration: the settings of the organizations named in its config and of the repositories those organizations and personal accounts own. It compares actual state against desired configuration defined in a Pkl file, reports drift, and can automatically fix discrepancies via `--fix`.

## Core Concepts

### Configuration Model

Desired state is defined in a **Pkl** configuration file. The schema lives in `config/drifty.pkl`; a concrete config `amends` it and lists the managed accounts and repositories (see `config/example.pkl` for a complete example).

drifty loads `./drifty.pkl` from the current working directory by default. A different file can be passed with `--config <path>`.

#### Owner Nesting

Two top-level mappings, both keyed by login, hold everything: `organizations` and `users`. A repository sits in the `repositories` listing of the account that owns it and carries no `owner` field, so a repository cannot name an owner that no other part of the config declares.

```pkl
organizations {
  ["example-org"] {
    description = "An example organization"
    repositories { (defaultRepo) { name = "example-service" } }
  }
}

users {
  ["example-user"] {
    repositories { (defaultRepo) { name = "personal-site" } }
  }
}
```

`Organization` and `User` are separate types rather than one type with a kind flag: a personal account has no org-level settings, and separate types make "org settings on a personal account" unrepresentable rather than settable and ignored.

#### Field Defaults

The `Repository` and `Organization` types in `config/drifty.pkl` declare defaults that match **GitHub's defaults** for a newly created repository or organization. A minimal repo entry (just a `name`) therefore represents a repo with GitHub's out-of-the-box settings and reports no drift against a freshly created repo; an organization nobody has touched reports no drift against an empty `organizations` entry.

Non-default desired values (e.g. disabling merge commits, enabling auto-merge) are set in shared templates in the config file, not in the schema.

#### Grouping Model

Repos are organized into groups that share defaults. Each group defines a `local` template `Repository`, and individual repos amend the template and override any field.

```pkl
// config — grouping model
local defaultRepo: Repository = new {
  allowMergeCommit = false
  allowAutoMerge = true
  deleteBranchOnMerge = true
  // ... org-wide policy overrides
}

organizations {
  ["example-org"] {
    repositories {
      (defaultRepo) { name = "repo-a"; description = "..." }
      (defaultRepo) { name = "repo-b"; description = "..."; topics { "library"; "java" } }
      // Per-repo overrides
      (defaultRepo) { name = "special-repo"; allowSquashMerge = true }
    }
  }
}
```

A template is an ordinary Pkl `local` binding and belongs to no account, so the same one can be amended by repositories under several owners.

### Org/Account Targeting

The accounts drifty works on are the keys of the `organizations` and `users` mappings. There is no CLI argument for them.

A single config may name any number of accounts. drifty lists each account's repositories once and hands that listing to both the organization check and the repository checks, so repository names only have to be unique within an account.

### Archived Repos

Repos marked `archived=true` in config are only checked for being archived. All other settings are skipped.

If a repo is configured as `archived=true` but is currently active, `--fix` will archive it.

### Partial Management

A repo declares which drift groups drifty manages, through the `managed` field on the Pkl `Repository`. Two modes:

```pkl
// strict, minus the groups someone else owns
(foreignOrgRepo) { managed { mode = "all_except"; groups { "action_secrets"; "rulesets" } } }

// partial: manage only these
(foreignOrgRepo) { managed { mode = "only"; groups { "repo_settings"; "topics" } } }
```

The default is `all_except` with an empty list, so a repo that declares nothing is checked exactly as it would be without the field.

Group names come from the `GroupName` typealias in `config/drifty.pkl`, which lists every group drifty can check on a repository. A name outside that union — including one of the organization groups — fails at config-eval time. A typo that silently left a group unmanaged is the dangerous failure here, so the union is what prevents it rather than a runtime check.

An unmanaged group is not fetched, not compared, and not fixed. Skipping only the comparison would still send the request, and a repo in an org someone else administers — the case this exists for — is where those requests return 403.

The report names unmanaged groups but not their values:

```
[OK]      repo-a
  Unmanaged: action_secrets, rulesets
```

Printing values would require fetching them, which is what the skipped requests avoid. `--fix` output omits the line: it reports what was applied, and groups nobody touched say nothing about that.

An archived repo whose `archived` group is unmanaged is checked for nothing at all — the archived short-circuit passes through the same filter, which is what declaring that group unmanaged asks for.

### Missing Repos

If a repo is listed in config but does not exist on GitHub, it is reported as `MISSING` and causes a non-zero exit code. drifty does not create repos — it only manages settings of existing repos.

## CLI Interface

### Commands

```
drifty                # Report drift; loads ./drifty.pkl by default
drifty --fix          # Apply all fixable changes
drifty --config <path> # Use a config file at an explicit path
drifty --state <path> # Use a state file at an explicit path
```

The config file defaults to `./drifty.pkl` in the working directory. If the resolved file does not exist, drifty prints `ERROR: config file not found: <path>` and exits with code 1.

The state file defaults to `drifty-state.json` next to the resolved config file. See [State File](#state-file).

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DRIFTY_GITHUB_TOKEN` | Yes | GitHub personal access token with repo, admin:org, workflow scopes |
| `DRIFTY_GITHUB_SECRETS` | No | JSON map of secret values (required for secret creation via `--fix`) |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | No drift detected |
| 1 | Drift detected, or errors occurred during fix |

### Output

**Default (no `--fix`):** Compact field-level diffs per repo, plus human-readable previews of what `--fix` would do. All repos are listed, including those with no drift.

```
[OK]      repo-a
[DRIFT]   repo-b:
            repo_settings.description: want=new value got=old value
            repo_settings.allow_auto_merge: want=true got=false
  Would fix: repo_settings
[UNKNOWN] repo-c: not in desired config
[MISSING] repo-d: in config but not found in org
[ERROR]   repo-e: 403 Forbidden
```

A diff path is the drift group's name followed by the setting's name within that group, which is what makes it unique across the run. Most groups use the setting's wire name; a few shorten it (`workflow_permissions.default` for `default_workflow_permissions`). Organizations are printed the same way, under their own heading — see [Report](#report) under Organizations.

**With `--fix`:** Same output, but diffs are replaced with per-setting fix results (FIXED or FAILED with reason). Failed fixes are also collected in a summary at the end.

## Managed Settings

All settings below are fields on the `Repository` type in `config/drifty.pkl`. Their defaults match GitHub's defaults for newly created repos — the "GitHub default" column documents these. Non-default desired values are set in shared templates in the config file.

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
| Discussions enabled | `false` | Yes | Yes |
| Is template | `false` | Yes | Yes |
| Allow forking (private repos) | `false` | Yes | Yes |
| Web commit signoff required | `false` | Yes | Yes |
| Squash merge commit title | `"COMMIT_OR_PR_TITLE"` | Yes | Yes |
| Squash merge commit message | `"COMMIT_MESSAGES"` | Yes | Yes |
| Merge commit title | `"MERGE_MESSAGE"` | Yes | Yes |
| Merge commit message | `"PR_TITLE"` | Yes | Yes |

### Security Settings

All configurable per-repo via the `Repository` class in `drifty.pkl`, with defaults matching GitHub's defaults.

| Setting | GitHub default (public repos) | Check | Fix |
|---------|-------------------------------|-------|-----|
| Vulnerability alerts (Dependabot alerts) | enabled | Yes | Yes |
| Automated security fixes (Dependabot security updates) | disabled | Yes | Yes |
| Secret scanning | enabled | Yes | Yes |
| Secret scanning push protection | enabled | Yes | Yes |
| Secret scanning validity checks | disabled | Yes | Yes |
| Secret scanning non-provider patterns | disabled | Yes | Yes |
| Secret scanning AI detection | disabled | Yes | Yes |
| Secret scanning delegated alert dismissal | disabled | Yes | Yes |
| Secret scanning delegated bypass | disabled | Yes | Yes |
| Private vulnerability reporting | disabled | Yes | Yes |
| Code scanning default setup | disabled | Yes | Yes |
| GitHub Advanced Security (GHAS) | disabled | Yes | Yes |

### Workflow Settings

| Setting | GitHub default | Check | Fix |
|---------|---------------|-------|-----|
| Default workflow permissions (read/write) | `"write"` | Yes | Yes |
| Can approve pull request reviews | `true` | Yes | Yes |

### Branch Protection (Legacy)

Managed via the `branchProtections` mapping (keyed by branch pattern) on the Pkl `Repository`. If a branch pattern entry is present, legacy branch protection is managed for that branch. If the mapping is empty, legacy protection is not managed (regardless of whether rulesets are configured). A repo can have both legacy protection and rulesets.

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

Repo-level rulesets managed via the `rulesets` mapping (keyed by ruleset name) on the Pkl `Repository`. drifty supports all GitHub ruleset rule types:

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

**Org-level rulesets are excluded.** The listing endpoint's `includes_parents` defaults to true, so rulesets inherited from the org arrive alongside the repo's own. They are not the repo's to reconcile — the repo endpoint cannot delete one — so drifty drops them before comparing.

### Required Status Checks

Status checks are defined on rulesets and branch protections. Shared `StatusCheck` values can be declared once as `local` bindings and reused; amending a ruleset appends additional checks to the inherited list:

```pkl
local baseRuleset: Ruleset = new {
  requiredStatusChecks { checkActions; codeqlAnalysis }
}

// Amends baseRuleset, appending one more required status check.
local mainCiRuleset: Ruleset = (baseRuleset) {
  requiredStatusChecks { mainCiCheck }
}
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

```pkl
(defaultRepo) {
  name = "my-repo"
  actionsSecrets { "PAT"; "DOCKER_HUB_ACCESS_TOKEN" }
}
```

**Check:** Verifies that each declared secret exists on the repo and is still
the value drifty last pushed. GitHub never returns a secret's value, so drifty
relies on the [state file](#state-file): it compares the recorded `updated_at`
timestamp and value hash against the current GitHub timestamp and the desired
value. A secret with no recorded baseline is reported as drift (`exists but
has no recorded baseline`).

**Fix:** `--fix` only pushes secrets that are drifted — missing, changed
out-of-band, rotated (config value changed), or lacking a recorded baseline.
Verified secrets are left untouched. After a push, drifty records the new `updated_at` and value
hash in the state file. If a value is not provided in `DRIFTY_GITHUB_SECRETS`,
the drift is reported as unfixable.

#### Secret Value Mapping

The `DRIFTY_GITHUB_SECRETS` env var contains a JSON map. Keys are formed by concatenating repo name, optional environment name, and secret name with hyphens:

```json
{
  "my-repo-PAT": "ghp_xxxx",
  "my-repo-production-TF_GITHUB_TOKEN": "ghp_yyyy"
}
```

- Repo action secret: `<repo>-<secret_name>`
- Environment secret: `<repo>-<environment>-<secret_name>`
- Org action secret: `org-<org>-<secret_name>`

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

## Organizations

Every key of the `organizations` mapping is checked as well as its repositories. Four drift groups cover it, named in the `OrgGroupName` typealias in `config/drifty.pkl`:

| Group | Endpoint |
|---|---|
| `org_settings` | `GET`/`PATCH /orgs/{org}` |
| `org_actions_permissions` | `/orgs/{org}/actions/permissions` and `.../selected-actions` |
| `org_workflow_permissions` | `/orgs/{org}/actions/permissions/workflow` |
| `org_action_secrets` | `/orgs/{org}/actions/secrets` |

Partial management works as it does per repository, through a `managed` block on the organization. Its `groups` listing is typed `OrgGroupName`, so naming a repository group there fails at config-eval rather than silently managing nothing.

An organization is never reported `UNKNOWN`: enumerating every organization the token can see is not drift. `MISSING` means the config names a login that 404s. Organization drift and organization errors count toward exit code 1 exactly as repository drift does.

### Organization Settings

The settings `PATCH /orgs/{org}` accepts. Defaults are GitHub's, so an organization nobody has touched reports no drift.

| Setting | Wire name | GitHub default |
|---|---|---|
| `displayName` | `name` | `""` |
| `description` | `description` | `""` |
| `websiteUrl` | `blog` | `""` |
| `company` | `company` | `""` |
| `email` | `email` | `""` |
| `location` | `location` | `""` |
| `twitterUsername` | `twitter_username` | `""` |
| `hasOrganizationProjects` | `has_organization_projects` | `true` |
| `hasRepositoryProjects` | `has_repository_projects` | `true` |
| `defaultRepositoryPermission` | `default_repository_permission` | `"read"` |
| `membersCanCreateRepositories` | `members_can_create_repositories` | `true` |
| `membersCanCreatePublicRepositories` | `members_can_create_public_repositories` | `true` |
| `membersCanCreatePrivateRepositories` | `members_can_create_private_repositories` | `true` |
| `membersCanCreateInternalRepositories` | `members_can_create_internal_repositories` | `false` |
| `membersCanCreatePages` | `members_can_create_pages` | `true` |
| `membersCanCreatePublicPages` | `members_can_create_public_pages` | `true` |
| `membersCanCreatePrivatePages` | `members_can_create_private_pages` | `true` |
| `membersCanForkPrivateRepositories` | `members_can_fork_private_repositories` | `false` |
| `webCommitSignoffRequired` | `web_commit_signoff_required` | `false` |
| `deployKeysEnabledForRepositories` | `deploy_keys_enabled_for_repositories` | `false` |

`GET /orgs/{org}` returns ten more settings that the `PATCH` accepts none of. drifty compares and reports them and never sends them; under `--fix` each is reported unfixed with the reason, the same null-`write` shape repository `visibility` uses.

| Setting | Wire name | GitHub default |
|---|---|---|
| `defaultRepositoryBranch` | `default_repository_branch` | `"main"` |
| `twoFactorRequirementEnabled` | `two_factor_requirement_enabled` | `false` |
| `membersCanDeleteRepositories` | `members_can_delete_repositories` | `true` |
| `membersCanChangeRepoVisibility` | `members_can_change_repo_visibility` | `true` |
| `membersCanInviteOutsideCollaborators` | `members_can_invite_outside_collaborators` | `true` |
| `membersCanDeleteIssues` | `members_can_delete_issues` | `false` |
| `membersCanCreateTeams` | `members_can_create_teams` | `true` |
| `membersCanViewDependencyInsights` | `members_can_view_dependency_insights` | `true` |
| `readersCanCreateDiscussions` | `readers_can_create_discussions` | `false` |
| `displayCommenterFullNameSettingEnabled` | `display_commenter_full_name_setting_enabled` | `false` |

Three groups of `PATCH` fields are deliberately absent from both tables. `billing_email` returns only to admins, so a config that named it would report drift for every non-admin token. `members_allowed_repository_creation_type` is a legacy overlap of the three `members_can_create_*_repositories` booleans. Every `*_enabled_for_new_repositories` security field carries an endpoint closing-down notice in GitHub's OpenAPI spec, superseded by code security configurations.

### Organization Actions Permissions

| Setting | GitHub default | Check | Fix |
|---|---|---|---|
| `enabledRepositories` | `"all"` | Yes | Yes |
| `allowedActions` | `"all"` | Yes | Yes |
| `shaPinningRequired` | `false` | Yes | Yes |
| `selectedActions` (GitHub-owned, verified, patterns) | unset | Yes | Yes |

The allow-list lives on a second endpoint and only exists under `allowedActions = "selected"`. drifty reads it whenever GitHub answers `allowed_actions = "selected"` — so an organization already in that mode draws the second request even from a config that declares no `selectedActions` — and writes it only when the config does declare one. Which repositories are selected under `enabledRepositories = "selected"` is not managed — see [Future Considerations](#future-considerations).

### Organization Workflow Permissions

| Setting | GitHub default | Check | Fix |
|---|---|---|---|
| `defaultWorkflowPermissions` | `"write"` | Yes | Yes |
| `canApprovePullRequestReviews` | `true` | Yes | Yes |

### Organization Action Secrets

Drift is detected against the [state file](#state-file) exactly as repository secrets are, with `visibility` and — under `visibility = "selected"` — `selectedRepositories` compared alongside. Config names repositories; the wire carries repository IDs, which drifty resolves from the listing it already performed for that account. A visibility-only change still re-pushes the value: the `PUT` requires `encrypted_value`, so there is no way to move a secret between visibilities without re-sending it.

Values come from `DRIFTY_GITHUB_SECRETS` under `org-<org>-<secret_name>`. The `org-` prefix is what keeps the key from colliding with a repository's `<repo>-<secret_name>` when an organization and a repository share a name — the map is flat and nothing else separates the two.

```json
{
  "org-example-org-NPM_TOKEN": "npm_xxxx"
}
```

Secrets on GitHub that the config does not declare are reported as extra and never deleted.

### Report

Organizations print above the repositories, under their own heading; a run over personal accounts alone prints neither heading.

```
=== Organizations ===
[DRIFT]   my-org:
            org_settings.description: want="..." got=""
            org_workflow_permissions.default_workflow_permissions: want=READ got=WRITE
  Would fix: org_settings, org_workflow_permissions

=== Repositories ===
[OK]      repo-a
```

`Orgs checked`, `Orgs OK`, `Orgs drifted`, `Orgs errored` and `Orgs missing`
join the summary under the same condition — one counter per status the section
above can print, so the summary never disagrees with the detail.

## State File

GitHub never returns a secret's value, so drifty cannot tell from the API
alone whether an existing secret is still correct. It keeps a small JSON state
file (default `drifty-state.json` next to the config file, override with
`--state <path>`) recording, per managed secret, the `updated_at` timestamp it
last observed and a salted SHA-256 hash of the value it last pushed.

```json
{
  "version": 1,
  "salt": "9f3c…",
  "repositories": {
    "my-repo": {
      "action_secrets": {
        "PAT": {
          "updated_at": "2026-01-02T03:04:05Z",
          "value_hash": "ab12…"
        }
      },
      "environment_secrets": {
        "production": {
          "TF_GITHUB_TOKEN": {
            "updated_at": "2026-01-02T03:04:05Z",
            "value_hash": "cd34…"
          }
        }
      }
    }
  },
  "organizations": {
    "my-org": {
      "action_secrets": {
        "NPM_TOKEN": {
          "updated_at": "2026-01-02T03:04:05Z",
          "value_hash": "ef56…"
        }
      }
    }
  }
}
```

`organizations` was added without a version bump: a file written before organization secrets existed simply has none, and the reader ignores properties it does not know, so `version` stays 1 and older state files load unchanged.

On each run drifty compares the recorded values against GitHub and the desired
config:

| GitHub state | State-file entry | Result |
|---|---|---|
| missing | — | drift (`missing`), `--fix` creates + records |
| exists | none recorded | drift (`no recorded baseline`), `--fix` pushes + records |
| exists | recorded, `updated_at` mismatch | drift (`changed outside drifty`), `--fix` re-pushes + records |
| exists | recorded, `updated_at` match, hash mismatch | drift (`config value changed`), `--fix` re-pushes + records |
| exists | recorded, both match | no drift (verified) |

`check` (read-only) never writes the state file; only `--fix` saves it, and
only when the file would say something: a run that records no secret leaves the
file absent, and a run whose records match the file byte for byte leaves it
untouched. A salt is not on its own worth a file — nothing recorded depends on
it yet, so the next run may generate a different one. The salt defeats rainbow
tables and hides equal values across secrets — it does not make a low-entropy
secret uncrackable offline.

## Unmanaged Repos

Repos that GitHub lists under an account the config names, but that the account's own `repositories` listing does not, are reported as `UNKNOWN` with a warning and cause a non-zero exit code.

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
- **Code security configurations** — the org-level replacement for the per-repo security toggles. The `*_enabled_for_new_repositories` fields left out of [Organization Settings](#organization-settings) are the API GitHub is closing down in their favour.
- **Custom properties** — manage the org-level property definitions, and their values per repo.
- **Org webhooks** — full lifecycle, alongside the repo webhooks below.
- **Teams and members** — team membership, org membership and role, and collaborator access per repo.
- **Runner groups** — self-hosted runner groups and which repositories may use them.
- **Actions variables** — org- and repo-level Actions variables, which are plaintext and so need no state file.
- **Actions repository selection** — which repositories are selected under `enabledRepositories = "selected"`. The config accepts the policy value and drifty writes it; GitHub keeps whatever selection the org already had.
- **GraphQL for bulk reads** — REST first, profile and optimize later.
- **Webhooks** — full lifecycle management of repo webhooks (URL, events, content type, secrets via `DRIFTY_GITHUB_SECRETS`).
- **Repository lifecycle** — create/delete/transfer repos is out of scope. drifty only manages settings of existing repos plus archival.
