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

The same goes for the `--fix` preflight that aborts over secrets missing from `DRIFTY_GITHUB_SECRETS`: it only counts secrets in a managed group. An unmanaged `action_secrets`, `environment_secrets` or `webhooks` declaration needs no value, because nothing will ever push it.

The report names unmanaged groups but not their values:

```
[OK]      repo-a
  Unmanaged: action_secrets, rulesets
```

Printing values would require fetching them, which is what the skipped requests avoid. `--fix` output omits the line: it reports what was applied, and groups nobody touched say nothing about that.

An archived repo whose `archived` group is unmanaged is checked for nothing at all — the archived short-circuit passes through the same filter, which is what declaring that group unmanaged asks for.

### Missing Repos

If a repo is listed in config but does not exist on GitHub, it is reported as `MISSING` and causes a non-zero exit code. drifty does not create repos — it only manages settings of existing repos.

### What `--fix` Deletes

Every keyed section reports an entry GitHub has and the config does not declare as `extra`. Whether `--fix` removes it depends on what the removal would discard:

- **Deleted** when the config can recreate everything the deletion discards: rulesets, branch protections, webhooks, deployment branch policies, and runner groups other than the default.
- **Reported and left alone** when the deletion discards something the config never held: secrets and variables (their values), environments (their secrets and history), custom property definitions (their values on every repository), code security configurations (their attachments), and every kind of membership — organization members, team members, collaborators, and repositories attached to a configuration. Each is reported with the reason `--fix` gives for not touching it.

## CLI Interface

### Commands

```
drifty                 # Report drift; loads ./drifty.pkl by default
drifty --fix           # Apply all fixable changes
drifty --config <path> # Use a config file at an explicit path
drifty --state <path>  # Use a state file at an explicit path
drifty --self-test     # Run the token- and network-free smoke test
drifty --version       # Print the version
drifty --help          # Print the usage; -h is the same flag
```

The config file defaults to `./drifty.pkl` in the working directory. If the resolved file does not exist, drifty prints `ERROR: config file not found: <path>` and exits with code 1.

The state file defaults to `drifty-state.json` next to the resolved config file. See [State File](#state-file).

`--help` and `--version` are answered before anything else, so neither needs `DRIFTY_GITHUB_TOKEN` and neither is affected by the other arguments given alongside it. `--help` writes the usage to stdout and exits 0; it is the only place the flags are listed for a user, and `usage_namesEveryArgumentDriftyAccepts` checks that list against the one drifty accepts.

**An argument drifty does not recognise refuses the whole invocation.** It prints `ERROR: unrecognised argument: <arg>` (`arguments:` for more than one, space-separated) and a line pointing at `--help`, and exits 1 without contacting GitHub. Ignoring them instead let a typo silently change what ran: `drifty --fixx` checked and exited 1 on drift, which reads as a fix that found nothing to do, and `drifty --confg other.pkl` checked `./drifty.pkl` — the file the flag was meant to replace. Every argument that would have been discarded is named, the value after an unrecognised flag included.

An option still takes whatever argument follows it, flag-shaped or not: `--config --fix` asks for a config file called `--fix` and fails on the file it cannot find, rather than being refused here. Reporting it as unrecognised would reject an invocation whose config path the rest of the run goes on to use.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DRIFTY_GITHUB_TOKEN` | Yes, except for `--help`, `--version` and `--self-test` | GitHub personal access token with repo, admin:org, workflow scopes |
| `DRIFTY_GITHUB_SECRETS` | No | JSON map of secret values (required for secret creation via `--fix`) |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | No drift detected; or `--help`, `--version`, `--self-test` or `--export` succeeded |
| 1 | Drift detected, errors occurred during fix, or the arguments were refused |

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

### Export

```
drifty --export <login> [<login> ...]  # Write current state as a starting config
drifty --export <login> --out <path>   # Write it somewhere other than ./export.pkl
drifty --export <login> --schema <uri> # Diff against, and amend, a schema other than main
```

`--export` reads every account named instead of checking one, and takes no `--config` and no `--fix` — combining either with `--export`, or naming none at all, is an error. It still needs `DRIFTY_GITHUB_TOKEN`; a personal account can only be exported by its own token, since `/user/repos` serves the authenticated user alone. The output defaults to `./export.pkl`; `--out` writes elsewhere, creating any directory in the path that does not exist yet. The exit code is 0 unless a named login could not be read at all, in which case it is 1 — a group within an account failing to read does not count, since the entry leaves that group unmanaged and notes why (below), and a scripted `drifty --export acme && drifty --fix` never reads the file to see it: a stderr line naming the count and the group names is printed once, after every login in the run, without changing the exit code. The file is written only when at least one login exported something; if every login failed, whatever `--out` already held is left alone rather than overwritten with an empty `organizations {}`.

The file amends the schema at `SchemaDefaults.MAIN_SCHEMA_URI` — `https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl` — unless `--schema` names a different one. Whichever URI is used is both written into the file's `amends` line and evaluated locally to get the defaults the export diffs against: exporting against one schema and amending another would put fields in the file that the schema it actually amends already implies, or leave out ones it does not.

**Only settings that differ from the schema's defaults are written.** A field at its default is omitted entirely rather than written with the default value — an organization or repository with nothing configured beyond GitHub's own defaults exports an empty entry. This is what lets the round-trip acceptance test (`ExportRoundTripTest`) tell a real gap in the exporter apart from a field that simply had nothing to say: a fixture built entirely at GitHub's defaults would pass whether or not the exporter worked at all, so its state is deliberately not at any defaults.

The file carries four kinds of `//` comment, each marking something a bare field could not say on its own:

| Note | Where it appears | What it means |
|---|---|---|
| Check-only setting | Beside a field GitHub returns but drifty never writes — `visibility` on a repository, and ten organization settings such as `twoFactorRequirementEnabled` | The field is exported so the file matches GitHub and reports no drift; the note says `--fix` will never act on it |
| Unreadable group | Where that group's section would otherwise sit | The token lacked the scope (or permission) for that group's own request, so the section is empty rather than absent — which is not the same as an empty section on GitHub. The group is also named in the entry's `managed` block, below |
| Secret value | Beside a section with a secret — action/environment secrets, webhooks with one configured | GitHub never returns a secret's value; supply it through `DRIFTY_GITHUB_SECRETS` before running `--fix` |
| Informational | A ruleset's required code scanning tools, or an archived repository | A required code scanning tool's alert thresholds are compared and exported as a tool name only, so the note says the thresholds exist on GitHub but not in the file; an archived repository skips every setting `RepositoryChecker` stops fetching once it is archived, replacing them all with one note rather than exporting stale or absent values |

The check-only note is paired with its field, never instead of it: leaving the field out would mean the file carries the schema's default forever while GitHub carries the real value, reporting drift no `--fix` could ever clear. The unreadable-group note is paired with the `managed` block that leaves the group alone — see below. The other two kinds stand alone, in place of the field or section they describe.

**An unreadable group is left unmanaged.** Every group whose read failed is named in the entry's own `managed` block, which `Managed`'s default mode of `all_except` reads as "drifty does not touch this one". Without it the note would be the only record, and a `//` comment is the one form a later run cannot act on: the next `drifty` run against the exported file would send exactly the request the export had already failed on, and the file the README points people at as their starting config would be one drifty cannot check. Dropping a group name from `managed` once the token can read it is what puts it back under drifty's care.

**What does not round-trip.** A freshly exported config that has any Actions secret, environment secret, or webhook with a secret configured reports `SecretMissingBaseline` drift ("exists but has no recorded baseline") on the very first `drifty` run against it — see [State File](#state-file). That is not a defect in the export: the state file is what records that a secret's value has been seen before, a freshly exported config has no state file yet, and GitHub never returns a secret's value for the export to seed one with. A hand-written config with the same secrets configured has the identical first-run drift. Every other exported field is expected to report zero drift against the account it came from, which is what `ExportRoundTripTest` checks.

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
| Target (`branch`, `tag`, `push`) | Yes | Yes |
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

**Push rulesets** are the same group with `target = "push"`. A push ruleset applies to every push to the repository, forks included, so it has no ref conditions: the schema refuses `includePatterns` and `excludePatterns` on one, and the request body carries no `conditions` at all. The rules GitHub accepts on a push ruleset are the file rules — `filePathRestrictions`, `maxFilePathLength`, `fileExtensionRestrictions`, `maxFileSize` — and they are compared and written exactly as on a branch ruleset. drifty does not check which rules a target accepts; a rule GitHub rejects for the target comes back as a failed fix with GitHub's message.

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
- Repo webhook secret: `<repo>-webhook-<name>`, where `<name>` is the key in `webhooks`
- Org webhook secret: `org-<org>-webhook-<name>`

### Action Variables

Actions variables are plaintext, so the value is compared directly and no state file is involved:

```pkl
(defaultRepo) {
  name = "my-repo"
  actionsVariables { ["REGION"] = "eu" }
  environments {
    ["production"] = new Environment { variables { ["TIER"] = "prod" } }
  }
}
```

A missing variable is created, a drifted one updated. Variables on GitHub that the config does not name are reported as extra and never deleted. Environment variables live on `Environment.variables` under the `environment_variables` group and are fetched one listing per environment, the way environment secrets are.

### Webhooks

GitHub has no name for a hook — every repository hook is called `web` — so the key in `webhooks` is drifty's, and the `url` is what matches a config entry to a hook on GitHub. Two config entries with the same url are a config error reported at check time; two GitHub hooks with the same url match the first.

```pkl
(defaultRepo) {
  name = "my-repo"
  webhooks {
    ["ci"] = new Webhook {
      url = "https://ci.example.com/hook"
      contentType = "json"
      events { "push"; "pull_request" }
      secret = true   // value under "my-repo-webhook-ci" in DRIFTY_GITHUB_SECRETS
    }
  }
}
```

| Setting | GitHub default | Check | Fix |
|---|---|---|---|
| `url` | — | identity | — |
| `contentType` | `"form"` | Yes | Yes |
| `insecureSsl` | `false` | Yes | Yes |
| `active` | `true` | Yes | Yes |
| `events` | `["push"]` | Yes | Yes |
| `secret` | `false` | Yes | Yes |

The secret is the one field GitHub never returns, so drift on it is detected the way secrets are: the [state file](#state-file) records the hook's `updated_at` and the salted hash of the value drifty last pushed, under `webhook_secrets` beside `action_secrets`. Every drift on a hook is fixed with one request carrying the whole desired config, secret included when declared, and the new `updated_at` is recorded. Hooks on GitHub that the config does not declare are deleted by `--fix`: a hook is nothing but its config.

### Custom Property Values

```pkl
(defaultRepo) {
  name = "my-repo"
  customProperties { ["tier"] = "gold"; ["internal"] = "true" }
  customMultiSelectProperties { ["tags"] { "java"; "cli" } }
}
```

Two mappings because a `multi_select` property holds a list. `true_false` properties take the strings `"true"` and `"false"`, which is how GitHub stores them. Only the properties the config names are compared: a property with a value on GitHub that the config does not mention is not drift, since the organization's schema — not the repository — decides which properties exist. The fix is one PATCH listing the drifted properties.

### Collaborators

```pkl
(defaultRepo) {
  name = "my-repo"
  collaborators { ["octocat"] = "push" }
  teamPermissions { ["core"] = "maintain" }
}
```

Direct collaborators only: a member who reaches the repository through an org role or a team is not a collaborator to reconcile. Permissions are the config's vocabulary (`pull`, `triage`, `push`, `maintain`, `admin`), read from the permission booleans GitHub returns. A user who has been invited but has not accepted appears in no listing, so the entry is reported missing until they accept; the PUT that fixes it is idempotent and does not resend the invitation. Team access is written through `PUT /orgs/{org}/teams/{slug}/repos/{owner}/{repo}`, so `teamPermissions` on a repository under a personal account is a config error reported at check time. Collaborators and teams on GitHub that the config does not list are reported and left in place.

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

Every key of the `organizations` mapping is checked as well as its repositories. Thirteen drift groups cover it, named in the `OrgGroupName` typealias in `config/drifty.pkl`:

| Group | Endpoint | Extras |
|---|---|---|
| `org_settings` | `GET`/`PATCH /orgs/{org}` | — |
| `org_actions_permissions` | `/orgs/{org}/actions/permissions`, `.../selected-actions` and `.../repositories` | — |
| `org_workflow_permissions` | `/orgs/{org}/actions/permissions/workflow` | — |
| `org_action_secrets` | `/orgs/{org}/actions/secrets` | reported |
| `org_action_variables` | `/orgs/{org}/actions/variables` | reported |
| `org_webhooks` | `/orgs/{org}/hooks` | deleted |
| `org_custom_properties` | `/orgs/{org}/properties/schema` | reported |
| `org_rulesets` | `/orgs/{org}/rulesets` | deleted |
| `org_code_security_configurations` | `/orgs/{org}/code-security/configurations` | reported |
| `org_teams` | `/orgs/{org}/teams` | reported |
| `org_members` | `/orgs/{org}/members`, `/orgs/{org}/memberships/{username}` | reported |
| `org_runner_groups` | `/orgs/{org}/actions/runner-groups` | deleted |

`GET /orgs/{org}` is sent even when `org_settings` is unmanaged: it is how drifty learns the organization exists, and any member can read it. Every other request is guarded by its group, so an organization on a plan without runner groups excludes `org_runner_groups` through `managed` and never sends the request that would 404.

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
| `selectedRepositories` | `[]` | Yes | Yes |

The allow-list lives on a second endpoint and only exists under `allowedActions = "selected"`. drifty reads it whenever GitHub answers `allowed_actions = "selected"` — so an organization already in that mode draws the second request even from a config that declares no `selectedActions` — and writes it only when the config does declare one. The repository selection is the same shape on a third endpoint: read whenever GitHub answers `enabled_repositories = "selected"`, compared when either side is `selected`, and written as ids resolved from the listing drifty already has for the account.

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

### Organization Action Variables

`OrgVariable` is the org twin of `OrgSecret` with a value: `value`, `visibility` and — under `visibility = "selected"` — `selectedRepositories` are compared, and one request writes all three. No state file is involved. Extra variables are reported and never deleted.

### Organization Webhooks

The same `Webhook` class as [repository webhooks](#webhooks), keyed the same way, with the secret under `org-<org>-webhook-<name>` and the state under the organization's `webhook_secrets`. Extra hooks are deleted by `--fix`.

### Custom Property Definitions

```pkl
customProperties {
  ["tier"] = new CustomProperty {
    valueType = "single_select"
    required = true
    defaultValue = "silver"
    allowedValues { "gold"; "silver" }
  }
  ["tags"] = new CustomProperty { valueType = "multi_select"; allowedValues { "java"; "cli" } }
}
```

| Setting | GitHub default | Check | Fix |
|---|---|---|---|
| `valueType` | — | Yes | Yes |
| `required` | `false` | Yes | Yes |
| `defaultValue` / `defaultValues` (multi_select) | unset | Yes | Yes |
| `description` | unset | Yes | Yes |
| `allowedValues` | `[]` | Yes | Yes |
| `valuesEditableBy` | `"org_actors"` | Yes | Yes |

One PUT per drifted property, which replaces the definition whole. Definitions whose `source_type` is `enterprise` are not the organization's to change and are dropped before comparing. Extra definitions are reported and never deleted: deleting one discards its value on every repository in the organization.

### Organization Rulesets

`OrgRuleset` extends `Ruleset`: every rule and condition the [repository group](#repository-rulesets) handles, plus the repository conditions only an organization ruleset has.

| Condition | Check | Fix |
|---|---|---|
| `repositoryNameInclude` / `repositoryNameExclude` | Yes | Yes |
| `repositoryNameProtected` | Yes | Yes |
| `repositoryPropertyInclude` / `repositoryPropertyExclude` | Yes | Yes |

`repository_id` conditions are not offered: the config names repositories, and a name condition covers the same ground without an id lookup. Rulesets whose `source_type` is `Enterprise` are dropped before comparing, on the organization side as on the repository side. Extra rulesets are deleted by `--fix`.

An organization ruleset takes every target a repository ruleset does plus `repository`. Neither a `push` nor a `repository` ruleset has refs to condition on, so the ref-name condition is left out of the request for both and the schema refuses ref patterns on them; the repository conditions above still say which repositories they cover. The `repository` target is the schema's to refuse on a repository ruleset, since only an organization has one.

### Code Security Configurations

```pkl
codeSecurityConfigurations {
  ["baseline"] = new CodeSecurityConfiguration {
    secretScanning = "enabled"
    secretScanningPushProtection = "enabled"
    defaultForNewRepos = "all"
    repositories { "example-service" }
  }
}
```

Defaults are GitHub's POST defaults, so a configuration created with only a name reports no drift. Every one of the sixteen `enabled`/`disabled`/`not_set` toggles, the description and the enforcement are compared; only configurations whose `target_type` is `organization` are, since the GitHub-provided global ones are not the organization's to change. Three writes, each its own fix so a rejected one is not reported as having failed the others: the settings go to a PATCH (a POST for a missing configuration), `defaultForNewRepos` to `PUT .../{id}/defaults`, and missing attachments to `POST .../{id}/attach` with `scope = selected`. Repositories attached outside the config are reported and left attached; extra configurations are reported and never deleted.

Three option sub-objects are managed only when the config sets them, so a configuration that leaves them out reports no drift for them and the PATCH omits them:

```pkl
codeScanningDefaultSetupOptions { runnerType = "labeled"; runnerLabel = "gpu" }
codeScanningOptions { allowAdvanced = true }
secretScanningDelegatedBypassOptions {
  reviewers { new { reviewerId = 5; reviewerType = "TEAM"; mode = "ALWAYS" } }
}
```

| Option | Check | Fix |
|---|---|---|
| `codeScanningDefaultSetupOptions.runnerType` (`standard`, `labeled`, `not_set`) | Yes | Yes |
| `codeScanningDefaultSetupOptions.runnerLabel` | Yes | Yes |
| `codeScanningOptions.allowAdvanced` | Yes | Yes |
| `secretScanningDelegatedBypassOptions.reviewers` (id, `TEAM`/`ROLE`, `ALWAYS`/`EXEMPT`) | Yes | Yes |

A runner label is required exactly when the runner type is `labeled`, and the schema says so. GitHub answers a configuration with no runner chosen as a null options object or as `not_set` with a null label; both read as `not_set`. `codeScanningOptions.allowAdvanced` decides whether repositories running their own code scanning workflow may stay attached to a configuration that enables default setup; GitHub omits the object, or answers the field within it as null, on a configuration that never set it. A reviewer GitHub returns without a `mode` predates the field and reads as `ALWAYS`, the schema's default. An empty `reviewers` listing wants none and clears them.

`dependency_graph_autosubmit_action_options` is not one of the three. GitHub returns it on every configuration — including the ones it provides itself, which never set it — so its one field is a plain setting rather than a nullable object:

| Setting | GitHub default | Check | Fix |
|---|---|---|---|
| `dependencyGraphAutosubmitLabeledRunners` | `false` | Yes | Yes |

It is compared on every configuration and always sent, the way the seventeen toggles are. `secret_scanning_extended_metadata` is not managed; `code_security` and `secret_protection` are not either, since the POST and PATCH accept them but `GET` returns neither, which would make them write-only and unverifiable — the reason `billing_email` is absent from the organization settings. drifty covers the same split through `advancedSecurity`'s `code_security` and `secret_protection` values.

### Teams

```pkl
teams {
  ["core"] = new Team {
    description = "Owns the services"
    maintainers { "octocat" }
    members { "hubot" }
  }
}
```

Teams are matched by slug. GitHub derives the slug from the name on creation, so a key that is not the slug of its own name is created under one slug and reported missing under the other on the next run; `name` defaults to the key. Membership is compared per role as two sets, read from `GET .../members?role=maintainer` and `?role=member`, and fixed with one `PUT .../memberships/{username}` per missing login. `parent` names a slug and is written as the id GitHub wants, resolved from the listing drifty already read. Members on GitHub that the config does not list, and teams the config does not declare, are reported and left in place. Team repository access is managed from the repository side by `teamPermissions`.

### Organization Members

```pkl
members { ["octocat"] = "admin"; ["hubot"] = "member" }
```

Two listings, `?role=admin` and `?role=member`, give every member's role without a request per user. A login missing from both is reported missing and fixed with `PUT /orgs/{org}/memberships/{login}`, which invites the user; a pending invitation is in neither listing, so the entry stays missing until they accept. Extras are reported and never removed.

### Runner Groups

```pkl
runnerGroups {
  ["gpu"] = new RunnerGroup {
    visibility = "selected"
    selectedRepositories { "example-service" }
    restrictedToWorkflows = true
    selectedWorkflows { "example-org/example-service/.github/workflows/train.yml@refs/heads/main" }
  }
}
```

| Setting | GitHub default | Check | Fix |
|---|---|---|---|
| `visibility` | `"all"` | Yes | Yes |
| `selectedRepositories` | `[]` | under `selected` | Yes |
| `allowsPublicRepositories` | `false` | Yes | Yes |
| `restrictedToWorkflows` | `false` | Yes | Yes |
| `selectedWorkflows` | `[]` | Yes | Yes |

The group GitHub marks `default` is never extra and never deleted; naming it in the config manages its settings. Selected repositories are read only for groups whose visibility is `selected` and written with `PUT .../repositories` as ids resolved from the account's listing. Every other undeclared group is deleted by `--fix`. Runner groups exist only on paid plans, so the group is one an operator on a free organization excludes through `managed`.

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
      },
      "webhook_secrets": {
        "audit": {
          "updated_at": "2026-01-02T03:04:05Z",
          "value_hash": "0123…"
        }
      }
    }
  }
}
```

`organizations` and `webhook_secrets` were added without a version bump: a file written before they existed simply has neither, and the reader ignores properties it does not know, so `version` stays 1 and older state files load unchanged. A repository record carries `webhook_secrets` beside its `action_secrets` and `environment_secrets`, keyed by the config's name for the hook.

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
- **Parallelism:** Virtual threads for concurrent repo checks/fixes, bounded by
  the client's in-flight request cap (see Rate Limiting)

### API Strategy

REST API only. Both reads and writes use the GitHub REST API v3. GraphQL for bulk reads is a future consideration.

### Rate Limiting

Monitor `X-RateLimit-Remaining` header and sleep until reset when exhausted.

`GitHubClient` also caps its own in-flight requests at 50. GitHub answers over
one HTTP/2 connection whose `SETTINGS_MAX_CONCURRENT_STREAMS` is 100, and the
JDK client does not queue past it — an account with more than ~100 configured
repositories would otherwise die on `too many concurrent streams` before
checking anything.

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

- **GraphQL for bulk reads** — REST first, profile and optimize later.
- **Repository lifecycle** — create/delete/transfer repos is out of scope. drifty only manages settings of existing repos plus archival.
- **Per-target rule validation for rulesets** — drifty writes whatever rules the config puts on a `push` or `repository` ruleset and lets GitHub reject the ones the target does not take; the schema could refuse them at config eval once the vocabulary is stable.
- **Custom repository roles** as collaborator permissions.
- **Enterprise-owned entities** of any kind — rulesets, custom properties, teams — drifty drops before comparing; managing them is the enterprise's API, not the organization's.
