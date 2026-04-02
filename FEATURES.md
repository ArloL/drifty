# Missing Features

## ~~1. Fix All Repository Settings (not just description)~~ DONE

Implemented: `applyFixes()` batches all drifted repo fields (description, homepage, has_issues, has_projects, has_wiki, allow_merge_commit, allow_squash_merge, allow_auto_merge, delete_branch_on_merge, archived) into a single PATCH call. Topics use a separate PUT endpoint via `replaceTopics()`. Topics checking was also added (config, state, diff, fix).

Note: desired values for these settings are currently hardcoded in `OrgChecker` — they need to be moved to `RepositoryArgs` fields (with GitHub defaults) and set via `defaultRepository` in `GitHubCheck.repositories()`.

## ~~2. Fix Security Settings~~ DONE

Implemented: `applyFixes()` now fixes all 4 security settings. Vulnerability alerts and automated security fixes each use dedicated PUT endpoints (`enableVulnerabilityAlerts()`, `enableAutomatedSecurityFixes()`). Secret scanning and push protection use a single PATCH call with a `security_and_analysis` payload via `updateRepository()`.

Note: desired values are currently hardcoded to enabled in `OrgChecker` — they need to be moved to `RepositoryArgs` fields (with GitHub defaults) and set via `defaultRepository` in `GitHubCheck.repositories()`. See Features 20 and 21.

## ~~3. Fix Workflow Settings~~ DONE

Implemented: `applyFixes()` now fixes workflow permissions drift. `GitHubClient.updateWorkflowPermissions()` sends a PUT to `/repos/{owner}/{repo}/actions/permissions/workflow`.

Note: desired values (`default_workflow_permissions: read`, `can_approve_pull_request_reviews: true`) are currently hardcoded in `OrgChecker` — they need to be moved to `RepositoryArgs` fields (GitHub defaults: `write` and `true` respectively) and set via `defaultRepository` in `GitHubCheck.repositories()`.

## ~~4. Fix Branch Protection~~ DONE

Implemented: `applyFixes()` now fixes branch protection drift for public repos. `GitHubClient.updateBranchProtection()` sends a PUT to `/repos/{owner}/{repo}/branches/{branch}/protection`. Both the "missing" and "drifted" cases are handled with a single PUT call.

Note: desired values (`enforce_admins: true`, `required_linear_history: true`, `allow_force_pushes: false`, `required_pull_request_reviews: null`, `restrictions: null`) are currently hardcoded in `OrgChecker` — they need to be moved to a `BranchProtectionArgs` config record and opt-in via a nullable `branchProtectionArgs` field on `RepositoryArgs`. See Features 16 and 17.

## ~~5. Repository Rulesets~~ DONE

Implemented: `RulesetArgs` config defines desired rulesets (name, include patterns, required linear history, no force pushes, required status checks, required review count). `GitHubClient` has full CRUD: `listRulesets()` and `getRuleset()` for reading, `createRuleset()` for creation, `updateRuleset()` for updates. `OrgChecker.checkRulesets()` diffs each desired ruleset against actual state (missing rulesets, include patterns, rule settings, status checks, review count). `applyFixes()` creates missing rulesets via POST and updates drifted ones via PUT, using `buildRulesetRequest()` to construct the payload with target `branch`, enforcement `active`, and all configured rules/conditions.

## ~~6. GitHub Pages Validation and Fixing~~ DONE

Pages endpoint is queried and the github-pages environment is auto-created, but no actual Pages settings are validated (build type, source branch/path, HTTPS enforcement) and no fixes are applied.

## ~~7. Secret Creation via `--fix`~~ DONE

Implemented: `applyFixes()` now fixes missing action secrets and environment secrets. `GITHUB_SECRETS` env var is parsed as a JSON map in `GitHubCheck.java` and passed to `OrgChecker`. Key format: `<repo>-<secret>` for action secrets, `<repo>-<env>-<secret>` for environment secrets. `GitHubClient` has `getActionSecretPublicKey()`, `createOrUpdateActionSecret()`, `getEnvironmentSecretPublicKey()`, and `createOrUpdateEnvironmentSecret()`. Secrets are encrypted using libsodium sealed-box via `com.goterl:lazysodium-java` before upload. If a secret's value is missing from the map, it stays in the remaining diffs as unfixable. New records `SecretPublicKeyResponse` and `SecretRequest` model the API payloads.

## ~~8. Environment Fixes (reviewers, wait timer, deployment branches)~~ DONE

Implemented: `EnvironmentArgs` extended with `waitTimer`, `deploymentBranchPolicy`, and `reviewers` fields (with builder methods). `EnvironmentDetailsResponse` replaces the former `Environment` record, parsing `protection_rules` (wait_timer, required_reviewers) and `deployment_branch_policy` from the API response, with `getWaitTimer()` and `getReviewerIds()` helpers. `GitHubClient.getEnvironments()` replaces `getEnvironmentNames()` returning full `EnvironmentDetailsResponse` objects; `updateEnvironment()` sends a PUT to `/repos/{owner}/{repo}/environments/{name}`. `RepositoryState` gains an `environmentDetails` map field. `OrgChecker.checkEnvironmentConfig()` diffs wait timer, deployment branch policy, and reviewer sets; `applyFixes()` calls `updateEnvironment()` via `buildEnvironmentUpdateRequest()` for any drifted environments.

## ~~9. Immutable Releases Validation~~ DONE

`RepositoryState` has `boolean immutableReleases` as the 13th field. `RepositoryArgs` has `immutableReleases` boolean field (default `false`) with getter, builder setter, equals/hashCode. `GitHubClient` has `enableImmutableReleases()` (PUT empty body, expects 204) and `disableImmutableReleases()` (DELETE, expects 204). `OrgChecker.fetchState()` calls `getImmutableReleases()` for non-archived repos and passes the boolean to `RepositoryState`. `OrgChecker.computeDiffs()` compares `desired.immutableReleases()` against `actual.immutableReleases()`. `OrgChecker.applyFixes()` calls enable or disable based on the diff. Diff and fix tests cover both enable and disable scenarios.

## ~~10. Owner as CLI Argument~~ DROPPED

Per spec update: the owner is hardcoded in the config code. No CLI argument needed.

## ~~11. Configurable Repo Groups with Defaults~~ DONE

Implemented: `RepositoryArgs.Builder` gained a `name(String)` setter (making `toBuilder()` usable as a group-defaults template) and an `addRequiredStatusChecks()` method that appends to the inherited list instead of replacing it. `GitHubCheck.repositories()` was reorganized into four named groups — `pagesSites` (6 repos sharing `.pages()`), `mainCiRepos` (9 repos sharing `main.required-status-check`), `individual` (unique configs), and `archived` — combined into a flat list via `Stream.of(...).flatMap(List::stream).toList()`. A new `RepositoryArgsTest` covers the builder additions.

## 12. Human-Readable Fix Previews

The default (non-fix) output should show human-readable previews of what `--fix` would do (e.g. `Would fix: enable auto-merge, update description`).

### Plan

- After printing drifts for a repo, compute and print human-readable descriptions of the fixes that would be applied.
- Format: `Would fix: <comma-separated list of actions>`.
- This requires the fix logic to be queryable without executing — extract fix descriptions as data before applying.

## ~~13. `--verbose` Flag~~ DROPPED

Per spec update: `--verbose` is dropped. The only CLI flag is `--fix`.

## ~~14. Allow Rebase Merge Check~~ DONE

Implemented: `allowRebaseMerge` field added to `RepositoryArgs` with default `true` (matching GitHub's default). Diff check added in `OrgChecker.checkRepoSettings()` comparing `RepositoryFull.allowRebaseMerge()` against config. Included in the PATCH payload for fixes via `desired.allowRebaseMerge()`.

## ~~15. Visibility Check (No Fix)~~ DONE

Implemented: Changed `RepositoryArgs.visibility()` from `String` to `RepositoryVisibility` enum (matching the API type). Added diff check in `OrgChecker.checkRepoSettings()` comparing `desired.visibility()` against `details.visibility()`. Visibility is check-only — not included in `applyFixes()` so it won't be modified even with `--fix`. Added test `drift_visibility_notMatching()`.

## ~~16. Required Pull Request Reviews in Branch Protection~~ DONE

Implemented: `BranchProtectionArgs` config record with PR review sub-settings: `requiredApprovingReviewCount`, `dismissStaleReviews`, `requireCodeOwnerReviews`, `requireLastPushApproval`. `RepositoryArgs` has `branchProtections` list (nullable = not managed). Added diff checks in `checkBranchProtection()` comparing desired PR review settings against actual. Fix payload includes PR reviews when configured. Tests cover drift detection and fix application.

Note: The branch protection is now fully encapsulated in `BranchProtectionArgs` which includes: `pattern` (branch name pattern), `enforceAdmins`, `requiredLinearHistory`, `allowForcePushes`, `requireConversationResolution`, `requiredStatusChecks`, plus PR review and restrictions settings. Multiple branch protections can be configured per repo via the list.

## ~~17. Branch Protection Restrictions~~ DONE

Implemented: `BranchProtectionArgs` includes `users`, `teams`, and `apps` lists for push restrictions. Diff checks compare desired restrictions against actual. Fix payload includes restrictions when configured. Tests cover users, teams, and apps restrictions.

## ~~18. Allow Update Branch~~ DONE

The spec lists "Allow update branch" as a managed setting. Not currently checked.

### Plan

- Add `allowUpdateBranch` field to `RepositoryArgs` (default: `false`, matching GitHub's default).
- Add diff check comparing against the API response field.
- Include in the PATCH payload for fixes.

## ~~19. Default Branch Fix~~ DONE

Implemented: `RepositoryArgs` now has a `defaultBranch` field (default: `"main"`, matching GitHub's default). The check was moved from the standalone `computeDiffs()` call (which used hardcoded `"main"`) to `checkRepoSettings()` where it reads `desired.defaultBranch()`. Fix logic was added to `applyFixes()` - the `default_branch` field is now included in the PATCH payload alongside other repo settings. Diff test `drift_defaultBranch_notMain` verifies detection, and fix test `unfixableDiffs_remainInList` was updated to verify the fix is applied. WireMock playback test added for `updateRepository` with `default_branch`.

## ~~20. Security Settings: Make Configurable Per-Repo~~ DONE

Implemented: `RepositoryArgs` now has four configurable boolean security fields: `vulnerabilityAlerts` (default `true`), `automatedSecurityFixes` (default `false`), `secretScanning` (default `true`), `secretScanningPushProtection` (default `true`). `OrgChecker.checkSecuritySettings()` reads desired values from config instead of hardcoding `true`. `applyFixes()` handles both enable and disable paths for all four settings — vulnerability alerts and automated security fixes use dedicated DELETE endpoints (`disableVulnerabilityAlerts()`, `disableAutomatedSecurityFixes()` added to `GitHubClient`); secret scanning settings use a single PATCH call with per-field status, fixing a pre-existing bug where `d.startsWith("secret_scanning")` also matched `secret_scanning_push_protection`. `defaultRepository` in `GitHubCheck.repositories()` sets `automatedSecurityFixes(true)`. WireMock mappings added for GET/DELETE automated-security-fixes and DELETE vulnerability-alerts; playback and recording tests updated.

## ~~21. Additional Security Settings~~ DONE

Implemented: `RepositoryArgs` has four new configurable boolean fields: `secretScanningValidityChecks` (default `false`), `secretScanningNonProviderPatterns` (default `false`), `privateVulnerabilityReporting` (default `false`), `codeScanningDefaultSetup` (default `false`). `SecurityAndAnalysis` record extended with `secretScanningValidityChecks` field. Two new client response records added: `PrivateVulnerabilityReporting` and `CodeScanningDefaultSetup`. `RepositoryState` extended with `privateVulnerabilityReporting` and `codeScanningDefaultSetup` boolean fields (fetched via dedicated GET endpoints). `GitHubClient` has `getPrivateVulnerabilityReporting()`, `enablePrivateVulnerabilityReporting()`, `disablePrivateVulnerabilityReporting()`, `getCodeScanningDefaultSetup()`, `enableCodeScanningDefaultSetup()`, `disableCodeScanningDefaultSetup()`. `OrgChecker.fetchState()` fetches both new settings for non-archived repos. `checkSecuritySettings()` diffs all four new settings. `applyFixes()` fixes: validity checks and non-provider patterns via `security_and_analysis` PATCH; private vulnerability reporting via PUT/DELETE; code scanning default setup via PATCH with `{"state": "configured"|"not-configured"}`. `defaultRepository` in `GitHubCheck.repositories()` enables `secretScanningValidityChecks`, `secretScanningNonProviderPatterns`, and `privateVulnerabilityReporting`. WireMock mappings added for all new GET/PUT/DELETE/PATCH endpoints; playback and diff/fix tests added.

## ~~22. Ruleset: All Rule Types~~ DONE

Implemented: Extended `Rule` sealed interface with new record types confirmed against the GitHub API schema (`schemas/repos/{owner}/{repo}/rules/branches/{branch}/get/schema.json`): `Creation`, `Deletion`, `RequiredSignatures` (no-param rules), `Update` (with `updateAllowsFetchAndMerge` boolean param), `CommitMessagePattern`, `CommitAuthorEmailPattern`, `CommitterEmailPattern`, `BranchNamePattern`, `TagNamePattern` (all using a shared `PatternParameters` record with `name`, `negate`, `operator`, `pattern`; operator is a `RulePatternArgs.PatternOperator` enum with values `STARTS_WITH`, `ENDS_WITH`, `CONTAINS`, `REGEX`), and `RequiredDeployments` (with `requiredDeploymentEnvironments` list). `RulesetArgs` extended with corresponding fields and builder methods. `OrgChecker.checkRulesets()` extended with diff checks for all new types; pattern rules use a `checkPatternRule()` helper. `buildRulesetRequest()` extended to build all new rule types.

## ~~23. Ruleset: Bypass Actors~~ DONE

Implemented: `BypassActorArgs` config record added (reusing `RulesetDetailsResponse.BypassActor.ActorType` and `BypassMode` enums). `RulesetArgs` has a `bypassActors` list (default empty). `RulesetRequest` extended with a `bypassActors` field (`@JsonInclude(NON_EMPTY)`) using a nested `BypassActorRequest` record that reuses the same enums (serializing correctly as `"Integration"`, `"OrganizationAdmin"`, etc.). `OrgChecker.checkRulesets()` diffs desired vs actual bypass actors as a set of `actorType:actorId:bypassMode` strings. `buildRulesetRequest()` maps `BypassActorArgs` to `BypassActorRequest`.

## ~~24. Ruleset: Delete Extra Rulesets~~ DONE

Implemented: `OrgChecker.checkRulesets()` now identifies actual rulesets not in desired config and adds `ruleset.<name>: extra` diffs. `applyFixes()` iterates extra rulesets and calls `GitHubClient.deleteRuleset()` for each. `GitHubClient.deleteRuleset(owner, repo, rulesetId)` sends `DELETE /repos/{owner}/{repo}/rulesets/{id}` and expects 204.

## 25. Missing Repo Detection

Repos in config that don't exist on GitHub should be reported as MISSING with exit code 1.

### Plan

- After fetching all org repos, compare against config list.
- Repos in config but not on GitHub get status MISSING.
- Include in report and cause non-zero exit.

## ~~26. Move Hardcoded Desired Values from OrgChecker to RepositoryArgs~~ DONE

Implemented: Added nine new fields to `RepositoryArgs` with GitHub-matching defaults:
- `hasIssues` (default: `true`)
- `hasProjects` (default: `true`)
- `hasWiki` (default: `true`)
- `allowMergeCommit` (default: `true`)
- `allowSquashMerge` (default: `true`)
- `allowAutoMerge` (default: `false`)
- `deleteBranchOnMerge` (default: `false`)
- `defaultWorkflowPermissions` (default: `WRITE` via `WorkflowPermissions.DefaultWorkflowPermissions` enum)
- `canApprovePullRequestReviews` (default: `true`)

Updated `OrgChecker.checkRepoSettings()` to read desired values from `RepositoryArgs` instead of using hardcoded literals. Updated `OrgChecker.checkWorkflowPermissions()` to accept a `desired` parameter and read `defaultWorkflowPermissions` and `canApprovePullRequestReviews` from config. Updated `OrgChecker.applyFixes()` to use `desired` values when constructing the PATCH payload for repo settings and workflow permissions. Set non-default desired values in `defaultRepository` in `GitHubCheck.repositories()`: `allowMergeCommit(false)`, `allowSquashMerge(false)`, `allowAutoMerge(true)`, `deleteBranchOnMerge(true)`, `defaultWorkflowPermissions(READ)`, `canApprovePullRequestReviews(true)`.

## ~~27. Additional Repository Settings~~ DONE

Implemented: Eight new settings added to `RepositoryArgs` (with GitHub-matching defaults) and wired into `checkRepoSettings()` and the PATCH payload in `applyFixes()`:
- `hasDiscussions` (default: `false`) — discussions tab
- `isTemplate` (default: `false`) — make repo available as a template
- `allowForking` (default: `false`) — allow forking of private repos
- `webCommitSignoffRequired` (default: `false`) — require sign-off on web-UI commits
- `squashMergeCommitTitle` (default: `"COMMIT_OR_PR_TITLE"`) — default title for squash merges
- `squashMergeCommitMessage` (default: `"COMMIT_MESSAGES"`) — default message for squash merges
- `mergeCommitTitle` (default: `"MERGE_MESSAGE"`) — default title for merge commits
- `mergeCommitMessage` (default: `"PR_TITLE"`) — default message for merge commits

All fields are already present in `RepositoryFull` (fetched from the API), so no new API calls were needed.

## 28. GitHub Advanced Security (GHAS)

Enable/disable GitHub Advanced Security per repo via the `advanced_security` field in `security_and_analysis`.

### Plan

- Add `advancedSecurity` boolean field to `RepositoryArgs` (default: `false`).
- Read `securityAndAnalysis.advancedSecurity().status()` in `checkSecuritySettings()`.
- Fix via PATCH `security_and_analysis.advanced_security.status` (`"enabled"` / `"disabled"`).
- Note: enabling GHAS on private repos requires a paid plan; fix failures should be reported but not fatal.

## 29. Secret Scanning AI Detection

Enable/disable AI-powered secret scanning detection per repo.

### Plan

- Add `secretScanningAiDetection` boolean field to `RepositoryArgs` (default: `false`).
- Read `securityAndAnalysis.secretScanningAiDetection().status()` in `checkSecuritySettings()`.
- Fix via PATCH `security_and_analysis.secret_scanning_ai_detection.status`.

## 30. Secret Scanning Delegated Alert Dismissal

Enable/disable delegated secret scanning alert dismissal per repo.

### Plan

- Add `secretScanningDelegatedAlertDismissal` boolean field to `RepositoryArgs` (default: `false`).
- Read `securityAndAnalysis.secretScanningDelegatedAlertDismissal().status()` in `checkSecuritySettings()`.
- Fix via PATCH `security_and_analysis.secret_scanning_delegated_alert_dismissal.status`.

## 31. Secret Scanning Delegated Bypass

Enable/disable delegated secret scanning bypass per repo, including configuration of bypass reviewers.

### Plan

- Add `secretScanningDelegatedBypass` boolean field to `RepositoryArgs` (default: `false`).
- Add `secretScanningDelegatedBypassReviewers` list field to `RepositoryArgs` (default: `[]`) — each entry specifies a reviewer (type + id, similar to `BypassActorArgs`).
- Read `securityAndAnalysis.secretScanningDelegatedBypass()` in `checkSecuritySettings()`.
- Diff the enabled status and the reviewer list.
- Fix via PATCH `security_and_analysis.secret_scanning_delegated_bypass` with `{"status": "enabled"|"disabled", "bypass_reviewers": [...]}`.
