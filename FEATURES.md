# Missing Features

## ~~1. Fix All Repository Settings (not just description)~~ DONE

Implemented: `applyFixes()` batches all drifted repo fields (description, homepage, has_issues, has_projects, has_wiki, allow_merge_commit, allow_squash_merge, allow_auto_merge, delete_branch_on_merge, archived) into a single PATCH call. Topics use a separate PUT endpoint via `replaceTopics()`. Topics checking was also added (config, state, diff, fix). Default branch remains check-only.

## ~~2. Fix Security Settings~~ DONE

Implemented: `applyFixes()` now fixes all 4 security settings. Vulnerability alerts and automated security fixes each use dedicated PUT endpoints (`enableVulnerabilityAlerts()`, `enableAutomatedSecurityFixes()`). Secret scanning and push protection use a single PATCH call with a `security_and_analysis` payload via `updateRepository()`. All desired values are hardcoded to enabled.

## ~~3. Fix Workflow Settings~~ DONE

Implemented: `applyFixes()` now fixes workflow permissions drift. `GitHubClient.updateWorkflowPermissions()` sends a PUT to `/repos/{owner}/{repo}/actions/permissions/workflow` with the desired state (`default_workflow_permissions: read`, `can_approve_pull_request_reviews: true`).

## ~~4. Fix Branch Protection~~ DONE

Implemented: `applyFixes()` now fixes branch protection drift for public repos. `GitHubClient.updateBranchProtection()` sends a PUT to `/repos/{owner}/{repo}/branches/{branch}/protection` with the full desired payload: `enforce_admins: true`, `required_linear_history: true`, `allow_force_pushes: false`, `required_status_checks` (strict: false, checks from `BASE_STATUS_CHECKS` + `RepositoryArgs.requiredStatusChecks()`), `required_pull_request_reviews: null`, `restrictions: null`. Both the "missing" and "drifted" cases are handled with a single PUT call.

## ~~5. Repository Rulesets~~ DONE

Implemented: `RulesetArgs` config defines desired rulesets (name, include patterns, required linear history, no force pushes, required status checks, required review count). `GitHubClient` has full CRUD: `listRulesets()` and `getRuleset()` for reading, `createRuleset()` for creation, `updateRuleset()` for updates. `OrgChecker.checkRulesets()` diffs each desired ruleset against actual state (missing rulesets, include patterns, rule settings, status checks, review count). `applyFixes()` creates missing rulesets via POST and updates drifted ones via PUT, using `buildRulesetRequest()` to construct the payload with target `branch`, enforcement `active`, and all configured rules/conditions.

## ~~6. GitHub Pages Validation and Fixing~~ DONE

Pages endpoint is queried and the github-pages environment is auto-created, but no actual Pages settings are validated (build type, source branch/path, HTTPS enforcement) and no fixes are applied.

## 7. Secret Creation via `--fix`

Secrets are checked for presence/absence but `--fix` does not create missing secrets.

### Plan

- Read `GITHUB_SECRETS` env var as a JSON map.
- Add `GitHubClient` methods:
  - GET `/repos/{owner}/{repo}/actions/secrets/public-key` to get the repo public key.
  - PUT `/repos/{owner}/{repo}/actions/secrets/{name}` to create/update a secret (libsodium sealed box encryption).
  - Same for environment secrets: `/repos/{owner}/{repo}/environments/{env}/secrets/{name}`.
- Key format from env var: `<repo>-<secret>` for action secrets, `<repo>-<env>-<secret>` for environment secrets.
- Add a dependency on a libsodium/NaCl library (e.g. `com.goterl:lazysodium-java`) for sealed box encryption.
- In `applyFixes()`, for each missing secret, look up value in the map and create it. If value not in map, report as unfixable.

## ~~8. Environment Fixes (reviewers, wait timer, deployment branches)~~ DONE

Implemented: `EnvironmentArgs` extended with `waitTimer`, `deploymentBranchPolicy`, and `reviewers` fields (with builder methods). `EnvironmentDetailsResponse` replaces the former `Environment` record, parsing `protection_rules` (wait_timer, required_reviewers) and `deployment_branch_policy` from the API response, with `getWaitTimer()` and `getReviewerIds()` helpers. `GitHubClient.getEnvironments()` replaces `getEnvironmentNames()` returning full `EnvironmentDetailsResponse` objects; `updateEnvironment()` sends a PUT to `/repos/{owner}/{repo}/environments/{name}`. `RepositoryState` gains an `environmentDetails` map field. `OrgChecker.checkEnvironmentConfig()` diffs wait timer, deployment branch policy, and reviewer sets; `applyFixes()` calls `updateEnvironment()` via `buildEnvironmentUpdateRequest()` for any drifted environments.

## 9. Immutable Releases Validation

`GitHubClient.getImmutableReleases()` exists but is never called in `computeDiffs()`.

### Plan

- Add `immutableReleases` boolean field to `RepositoryArgs`.
- In `computeDiffs()`, call `getImmutableReleases()` and compare against the desired value.
- In `applyFixes()`, call the endpoint to enable/disable immutable releases.
- Add `GitHubClient.updateImmutableReleases(owner, repo, enabled)`.

## 10. Owner as CLI Argument (not hardcoded)

The spec says the owner/org is a CLI argument. Currently it's hardcoded to "ArloL".

### Plan

- Parse the first positional CLI argument as the owner.
- Print usage and exit with code 1 if no owner is provided.
- Pass owner through to `OrgChecker` instead of using a hardcoded value.

## ~~11. Configurable Repo Groups with Defaults~~ DONE

Implemented: `RepositoryArgs.Builder` gained a `name(String)` setter (making `toBuilder()` usable as a group-defaults template) and an `addRequiredStatusChecks()` method that appends to the inherited list instead of replacing it. `GitHubCheck.repositories()` was reorganized into four named groups — `pagesSites` (6 repos sharing `.pages()`), `mainCiRepos` (9 repos sharing `main.required-status-check`), `individual` (unique configs), and `archived` — combined into a flat list via `Stream.of(...).flatMap(List::stream).toList()`. A new `RepositoryArgsTest` covers the builder additions.

## 12. Output: Show API Calls That `--fix` Would Make

The spec says the default (non-fix) output should show the API calls that `--fix` would make (e.g. `Would fix: PATCH /repos/...`).

### Plan

- After printing drifts for a repo, compute and print the API calls that would be made.
- Format: `Would fix: METHOD /path {payload}`.
- This requires the fix logic to be queryable without executing — extract fix plans as data before applying.

## 13. `--verbose` Flag

The spec mentions a `--verbose` flag. Not implemented.

### Plan

- Add `--verbose` CLI flag parsing alongside `--fix`.
- Pass verbosity to `OrgChecker`.
- In verbose mode, print additional detail: full API responses, request/response headers, rate limit status.

## 14. Allow Rebase Merge Check

The spec lists "Allow rebase merge" as a managed setting. The current `RepositoryArgs` has `allowMergeCommit` and `allowSquashMerge` but not `allowRebaseMerge`.

### Plan

- Add `allowRebaseMerge` field to `RepositoryArgs`.
- Add diff check comparing `RepositoryFull.allowRebaseMerge()` against config.
- Include in the PATCH payload for fixes.

## 15. Visibility Check and Fix

The spec lists visibility (public/private) as a managed setting. Not currently checked.

### Plan

- Add `visibility` field to `RepositoryArgs` (enum: PUBLIC, PRIVATE).
- Check `RepositoryFull.visibility()` or `isPrivate()` against desired value.
- Fix via PATCH `/repos/{owner}/{repo}` with `"visibility"` field.

## 16. Required Pull Request Reviews in Branch Protection

The spec lists "Required pull request reviews" as a branch protection setting. Not currently checked.

### Plan

- Add `requiredPullRequestReviews` config to `RepositoryArgs` (dismiss stale reviews, required approving review count, etc.).
- Parse from `BranchProtection` response.
- Add diff logic and include in branch protection update payload.

## 17. Branch Protection Restrictions

The spec lists "Restrictions" as a branch protection setting. Not currently checked.

### Plan

- Add `restrictions` config to `RepositoryArgs` (users, teams, apps that can push).
- Parse from `BranchProtection` response.
- Add diff logic and include in branch protection update payload.
