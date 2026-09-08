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

Implemented: `applyFixes()` now fixes missing action secrets and environment secrets. `DRIFTY_GITHUB_SECRETS` env var is parsed as a JSON map in `GitHubCheck.java` and passed to `OrgChecker`. Key format: `<repo>-<secret>` for action secrets, `<repo>-<env>-<secret>` for environment secrets. `GitHubClient` has `getActionSecretPublicKey()`, `createOrUpdateActionSecret()`, `getEnvironmentSecretPublicKey()`, and `createOrUpdateEnvironmentSecret()`. Secrets are encrypted using libsodium sealed-box via `com.goterl:lazysodium-java` before upload. If a secret's value is missing from the map, it stays in the remaining diffs as unfixable. New records `SecretPublicKeyResponse` and `SecretRequest` model the API payloads.

## ~~8. Environment Fixes (reviewers, wait timer, deployment branches)~~ DONE

Implemented: `EnvironmentArgs` extended with `waitTimer`, `deploymentBranchPolicy`, and `reviewers` fields (with builder methods). `EnvironmentDetailsResponse` replaces the former `Environment` record, parsing `protection_rules` (wait_timer, required_reviewers) and `deployment_branch_policy` from the API response, with `getWaitTimer()` and `getReviewerIds()` helpers. `GitHubClient.getEnvironments()` replaces `getEnvironmentNames()` returning full `EnvironmentDetailsResponse` objects; `updateEnvironment()` sends a PUT to `/repos/{owner}/{repo}/environments/{name}`. `RepositoryState` gains an `environmentDetails` map field. `OrgChecker.checkEnvironmentConfig()` diffs wait timer, deployment branch policy, and reviewer sets; `applyFixes()` calls `updateEnvironment()` via `buildEnvironmentUpdateRequest()` for any drifted environments.

## ~~9. Immutable Releases Validation~~ DONE

`RepositoryState` has `boolean immutableReleases` as the 13th field. `RepositoryArgs` has `immutableReleases` boolean field (default `false`) with getter, builder setter, equals/hashCode. `GitHubClient` has `enableImmutableReleases()` (PUT empty body, expects 204) and `disableImmutableReleases()` (DELETE, expects 204). `OrgChecker.fetchState()` calls `getImmutableReleases()` for non-archived repos and passes the boolean to `RepositoryState`. `OrgChecker.computeDiffs()` compares `desired.immutableReleases()` against `actual.immutableReleases()`. `OrgChecker.applyFixes()` calls enable or disable based on the diff. Diff and fix tests cover both enable and disable scenarios.

## ~~10. Owner as CLI Argument~~ DROPPED

Per spec: the owner is the `owner` field on each repository in the config.
`OrgChecker.check()` lists the repositories of each distinct owner it finds and
checks every repository under the owner its own entry declares, so one config
can span several owners. No CLI argument needed.

## ~~11. Configurable Repo Groups with Defaults~~ DONE

Implemented: `RepositoryArgs.Builder` gained a `name(String)` setter (making `toBuilder()` usable as a group-defaults template) and an `addRequiredStatusChecks()` method that appends to the inherited list instead of replacing it. `GitHubCheck.repositories()` was reorganized into four named groups — `pagesSites` (6 repos sharing `.pages()`), `mainCiRepos` (9 repos sharing `main.required-status-check`), `individual` (unique configs), and `archived` — combined into a flat list via `Stream.of(...).flatMap(List::stream).toList()`. A new `RepositoryArgsTest` covers the builder additions.

## ~~12. Human-Readable Fix Previews~~ DONE

Implemented: in check mode (no `--fix`), `OrgChecker.checkOne()` collects the
names of the drift groups that detected drift (the keys of `groupDrifts`, which
only contains groups with non-empty fixes) and passes them as a `fixPreview`
list on `CheckResult.RepoCheckResult.drift(name, diffs, fixPreview)`.
`printReport()` prints a `  Would fix: <group1, group2, ...>` line after the
drift list for each drifted repo. The preview is derived without executing any
fix — it reflects the groups whose `--fix` action would run. In `--fix` mode no
preview is emitted (the fixes are applied instead). Group names (e.g.
`repo_settings`, `advanced_security`, `topics`) are the human-readable tokens;
`CheckResultTest` covers that `drift(...)` carries and defaults the preview.

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

## ~~25. Missing Repo Detection~~ DONE

Implemented: `OrgChecker.check()` compares the configured repositories against
the set of repo names returned by `listOrgRepos()`; any config repo not found
in the org is added as a `CheckResult.RepoCheckResult.missing(name)` with the
`MISSING` status. `printReport()` prints a `[MISSING] <name>: in config but not
found in org` line and a `Missing:` summary count (`CheckResult.missingCount()`).
`CheckResult.hasDrift()` now returns `true` when `missingCount() > 0`, so a
missing repo causes a non-zero exit code. `CheckResultTest` covers
`missingCount()` and the `hasDrift()` behaviour for missing/unknown repos.

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
- `allowForking` (default: `true`) — allow forking of private repos
- `webCommitSignoffRequired` (default: `false`) — require sign-off on web-UI commits
- `squashMergeCommitTitle` (default: `"COMMIT_OR_PR_TITLE"`) — default title for squash merges
- `squashMergeCommitMessage` (default: `"COMMIT_MESSAGES"`) — default message for squash merges
- `mergeCommitTitle` (default: `"MERGE_MESSAGE"`) — default title for merge commits
- `mergeCommitMessage` (default: `"PR_TITLE"`) — default message for merge commits

All fields are already present in `RepositoryFull` (fetched from the API), so no new API calls were needed.

## ~~28. GitHub Advanced Security (GHAS)~~ DONE

Implemented: `RepositoryArgs` has an `advancedSecurity` boolean field (default
`false`) wired through the Pkl schema (`config/drifty.pkl`) and `PklConfigLoader`.
`AdvancedSecurityDriftGroup` diffs `desired.advancedSecurity()` against the
actual `securityAndAnalysis.advancedSecurity().status()` (read via the
`OrgChecker.securityFlag()` helper) and fixes via PATCH
`security_and_analysis.advanced_security.status` (`"enabled"`/`"disabled"`).
Enabling GHAS on a private repo that lacks a paid plan makes the PATCH fail;
`applyFixes()` already catches per-group fix exceptions and leaves the diff in
the remaining list, so such failures are reported but not fatal.

## ~~29. Secret Scanning AI Detection~~ DONE

Implemented: `RepositoryArgs` has a `secretScanningAiDetection` boolean field
(default `false`) wired through the Pkl schema and `PklConfigLoader`.
`SecretScanningAiDetectionDriftGroup` diffs against
`securityAndAnalysis.secretScanningAiDetection().status()` and fixes via PATCH
`security_and_analysis.secret_scanning_ai_detection.status`.

## ~~30. Secret Scanning Delegated Alert Dismissal~~ DONE

Implemented: `RepositoryArgs` has a `secretScanningDelegatedAlertDismissal`
boolean field (default `false`) wired through the Pkl schema and
`PklConfigLoader`. `SecretScanningDelegatedAlertDismissalDriftGroup` diffs
against `securityAndAnalysis.secretScanningDelegatedAlertDismissal().status()`
and fixes via PATCH
`security_and_analysis.secret_scanning_delegated_alert_dismissal.status`.

## ~~31. Secret Scanning Delegated Bypass~~ DONE

Implemented: `RepositoryArgs` has a `secretScanningDelegatedBypass` boolean
(default `false`) and a `secretScanningDelegatedBypassReviewers` list of
`SecretScanningBypassReviewerArgs` (reviewer id + `ReviewerType` enum
`TEAM`/`ROLE`), wired through the Pkl schema (`SecretScanningBypassReviewer`
class + `SecretScanningBypassReviewerType` typealias) and `PklConfigLoader`. The
client `SecurityAndAnalysis` record gained a `secretScanningDelegatedBypassOptions`
field (`DelegatedBypassOptions` holding a `List<BypassReviewer>`) so the same
record models both the GET response and the PATCH request.
`SecretScanningDelegatedBypassDriftGroup` diffs the enabled status and — only
when enabled — the reviewer set (compared as `TYPE:id` strings). The fix sends a
single PATCH with `security_and_analysis.secret_scanning_delegated_bypass.status`
and, when enabled, `security_and_analysis.secret_scanning_delegated_bypass_options.reviewers`
(`reviewer_id` + `reviewer_type`). Actual reviewers are read via the
`OrgChecker.bypassReviewers()` helper.

## ~~32. Pkl as the Default Configuration~~ DONE

Implemented: Pkl is now the only configuration mechanism. The hardcoded Java config method `GitHubCheck.repositories()` was removed. `GitHubCheck.main()` resolves a config path — the value of `--config <path>` when supplied, otherwise `./drifty.pkl` in the working directory — and exits with code 1 and `ERROR: config file not found: <path>` if it is missing. `PklConfigLoaderTest` was reworked from a Java/Pkl equivalence check into a smoke test that loads `config/ArloL.pkl`. `SPEC.md` was updated to describe the Pkl configuration model.

## ~~33. Secret Drift Detection via State File~~ DONE

Implemented: drifty keeps a JSON state file (default `drifty-state.json` next to the config file, override with `--state <path>`) recording, per managed secret, the `updated_at` timestamp it last observed and a salted SHA-256 hash of the value it last pushed. New package `io.github.arlol.githubcheck.state` holds `DriftyState` (in-memory model with `hash()`, `recordActionSecret()`/`recordEnvironmentSecret()` and nullable record lookups) and `StateStore` (load/save). `GitHubClient.getActionSecretNames()`/`getEnvironmentSecretNames()` were renamed to `getActionSecrets()`/`getEnvironmentSecrets()` returning `List<Secret>`; `getActionSecret()`/`getEnvironmentSecret()` were added to read a single secret's `updated_at` after a PUT. `RepositoryState` now carries `List<Secret>`. The secret drift groups compare the recorded timestamp and value hash: a missing timestamp baseline is `SecretMissingBaseline` ("exists but has no recorded baseline"), a timestamp mismatch is `SecretChanged` ("changed outside drifty"), a value-hash mismatch is `SecretValueChanged` ("config value changed since last push"). `--fix` only pushes drifted secrets and records the new timestamp and hash; `check` never writes the state file. This detects both out-of-band edits and secret rotation.

## ~~34. Partial Management: Per-Repo Managed Drift Groups~~ DONE

Implemented: each repo declares which drift groups drifty manages, via a
`managed` field on the Pkl `Repository` — `mode = "all_except"` (the default,
with an empty list, so existing config needs no migration) or `mode = "only"`,
plus a `groups` listing. Group names became a `GroupName` typealias in
`config/drifty.pkl` listing all 23 groups, so codegen emits the enum and
`DriftGroup.name()` returns `Drifty.GroupName` instead of a `String`; generated
enums carry their source string, so drift paths and the `Would fix:` line print
unchanged. `DriftPathNamespacingTest` asserts the group list and the enum's
constants match in both directions, replacing a hand-maintained mirror list.

`ManagedGroups` (new, in `drift`) turns the declaration into a predicate:
`of(Drifty.Managed)`, `all()`, `manages(GroupName)` and `unmanaged()`.
`OrgChecker.createDriftGroups` derives it from `desired.managed` internally —
keeping its signature, and with it 92 test call sites — and drops unmanaged
groups in a single `onlyManaged` filter over the finished list, so a group added
later is filtered without its author knowing the feature exists. The archived
short-circuit passes through the same filter.

`OrgChecker.fetchState` takes the `ManagedGroups` as a third parameter and
guards each fetch, because filtering the group alone would still send its
requests and a repo in a foreign org is where those 403. `fetchSecurityFlags`
guards its five flags with short-circuiting `&&`. `RepositoryState.workflowPermissions`
is null when that group is unmanaged, since it holds the response type rather
than an `Optional` and nothing reads it once the group is filtered out.
`CheckResult.RepoCheckResult` gained an `unmanaged` component, printed as an
`Unmanaged:` line for OK and DRIFT repos — group names only, since their values
were never fetched.

Also fixed here: `OrgChecker.fetchRulesets` now drops rulesets whose
`sourceType` is `ORGANIZATION`. The listing endpoint's `includes_parents`
defaults to true, and `RulesetSourceType` was parsed but read nowhere, so
`RulesetDriftGroup` reported every org ruleset as `extra (should not exist)` and
`--fix` issued a repo-scoped DELETE that endpoint cannot serve.

## ~~35. Write the State File Only When It Has Something to State~~ DONE

Implemented: `StateStore.save` no longer writes unconditionally. It returns
early when `DriftyState.isEmpty()` — no repository holds a secret record — so a
`--fix` run over a config with no managed secrets, or one that pushes nothing,
creates no `drifty-state.json` at all. Otherwise it serializes to a byte array
and skips the write when the existing file already holds exactly those bytes,
so a repeat `--fix` does not touch the file's mtime.

A salt generated during the run does not count as content: it is only
meaningful next to the hashes it produced, and with no record persisted the
next run is free to generate a different one. `isEmpty()` is `@JsonIgnore`d
because `DriftyState` is serialized with field visibility `ANY`, which leaves
Jackson's default public-getter detection in place — an `isX()` method would
otherwise be written into the file as a field.

## ~~36. Organization Settings~~ DONE

Implemented: `config/drifty.pkl` grew two top-level mappings keyed by login,
`organizations: Mapping<String, Organization>` and `users: Mapping<String,
User>`, and `Repository.owner` went away — a repository's owner is now the key
of the block it sits in, so a config can no longer name an owner nothing else
declares. `User` is a separate class holding only `repositories`, which makes
org settings on a personal account unrepresentable rather than settable and
ignored. `config/ArloL.pkl` was replaced by `config/example.pkl`, which names
no real account and exists so `./mvnw exec:java`, `PklConfigLoaderTest` and
SPEC.md have a complete config to point at.

Four organization drift groups, named in a new `OrgGroupName` typealias:
`org_settings` (thirty settings from `GET /orgs/{org}`, twenty of which the
PATCH accepts and ten of which it does not), `org_actions_permissions` (the
policy plus the `selected-actions` allow-list, two endpoints and so two
`DriftFix` values), `org_workflow_permissions` and `org_action_secrets`.
`OrgManaged` mirrors `Managed` over `OrgGroupName`, so a repository group named
in an organization's `managed` block fails at config-eval.

`DriftGroup` became `DriftGroup<N extends Enum<N>>` and `ManagedGroups` became
`ManagedGroups<N>` built from a class token, so one `detect()` namespaces both
scopes and neither scope's group names can be used in the other. Existing
groups changed their `extends` clause and nothing else.

`OrgChecker` was renamed `RepositoryChecker` — with organizations in the
picture, a class called `OrgChecker` that checks repositories misdirects every
reader — and the new `OrganizationChecker` sits beside it. `GitHubCheck.main`
lists each account's repositories once and hands the listing to both, which is
also what lets the org secrets group turn configured repository names into the
IDs the secrets endpoint wants. `DriftFixer` and `Report` were extracted from
the old checker; `CheckResult` became `CheckResult(List<Entry> orgs,
List<Entry> repos)` with `RepoCheckResult` renamed `Entry` and shared by both
sections. Organizations never report `UNKNOWN`: enumerating every organization
a token can see is not drift.

`OrgSettingsDriftGroup` follows `RepoSettingsDriftGroup` exactly — a `Setting`
table pairing each comparison with its builder call, a PATCH body built from
the drifted entries alone, and a per-field re-send when a multi-field request
is rejected. `members_can_create_internal_repositories` is the reason: GitHub
422s it on any organization outside Enterprise, even when it already holds the
wanted value, so a body built from the desired config would fail a description
change over a setting that had not drifted. The ten settings the PATCH does not
accept carry a null `write` and report as unfixed, the shape repository
`visibility` already used.

`DriftyState` gained `organizations` beside `repositories`, holding the same
`updated_at` and salted value hash per secret. Values come from
`DRIFTY_GITHUB_SECRETS` under `org-<org>-<secret>`; the prefix is what keeps
the key from colliding with a repository's `<repo>-<secret>` when an
organization and a repository share a name. The key was added without a version
bump, and `isEmpty()` accounts for org records so a run that records only those
still writes the file.

## ~~37. Environment Reviewers, Branch Policies and Extra Environments~~ DONE

Implemented: `Environment` gained `preventSelfReview`, `reviewerUsers`,
`reviewerTeams`, `protectedBranches`, `customBranchPolicies`,
`deploymentBranchPatterns` and `deploymentTagPatterns`. Reviewers are compared
as the set of `User:<login>` and `Team:<slug>` strings; GitHub's PUT wants
numeric ids, so the fix resolves each through `GET /users/{login}` and
`GET /orgs/{org}/teams/{slug}` at fix time only. Deployment branch policies are
compared as `branch:<pattern>` / `tag:<pattern>` strings, read only when
either side has custom policies on (the listing 404s otherwise), and each
policy is its own `DriftFix`: a missing one is created, an extra one deleted.
Extra environments are reported and left alone.

## ~~38. Branch Protection Completions~~ DONE

Implemented: `strictStatusChecks`, `allowDeletions`, `blockCreations`,
`lockBranch`, `allowForkSyncing`, the dismissal restrictions
(`dismissalUsers` / `dismissalTeams` / `dismissalApps`) and the bypass
allowances (`bypassPullRequestUsers` / `-Teams` / `-Apps`) — the rest of the
PUT body. `strictStatusChecks` replaces the `false` the comparison and the
request had hardcoded.

## ~~39. Ruleset Completions and `RulesetComparison`~~ DONE

Implemented: `Ruleset` gained `target`, `enforcement`, `excludePatterns`,
`strictRequiredStatusChecks`, the full `pullRequest` parameters (replacing
`requiredReviewCount`; the rule's presence is what says pull requests are
required), `mergeQueue`, `workflows`, `filePathRestrictions`,
`maxFilePathLength`, `fileExtensionRestrictions` and `maxFileSize`. The
comparison and request building moved out of `RulesetDriftGroup` into
`RulesetComparison`, because the organization group needs the same code with
a different endpoint.

## ~~40. Actions Variables~~ DONE

Implemented: `action_variables`, `environment_variables` and
`org_action_variables`. Plaintext, so the value is compared directly and no
state file is involved; a missing variable is POSTed and a drifted one
PATCHed. `OrgVariable` is `OrgSecret` with a value, and its selected
repositories are resolved and compared the way the org secrets group's are.
Extras are reported and never deleted, since deleting one discards a value the
config never held.

## ~~41. Webhooks~~ DONE

Implemented: `webhooks` and `org_webhooks` over one `Webhook` class, keyed by
a name of the operator's choosing because GitHub has no name for a hook; the
url is the identity. The secret is the one field GitHub never returns, so it
is handled the way secrets are: `DriftyState` gained `webhook_secrets` on both
the repository and the organization record, the `--fix` preflight counts a
hook with `secret = true` under `<repo>-webhook-<name>` and
`org-<org>-webhook-<name>`, and `WebhookReconciler` — the logic the two groups
share — fixes every drift with one request carrying the whole config and
records the new `updated_at`. Extra hooks are deleted: a hook is nothing but
its config.

## ~~42. Custom Properties~~ DONE

Implemented: `org_custom_properties` manages the definitions and
`custom_properties` the values. Two value mappings on the repository
(`customProperties` and `customMultiSelectProperties`) because Pkl's codegen
turns `String | Listing<String>` into `Object`; `ActualTypes` splits a
`default_value` or `value` by its JSON shape the same way. Only the properties
the config names are compared on a repository, since the organization's schema
decides which exist. Enterprise-owned definitions are dropped before comparing
and extra definitions are reported, never deleted.

## ~~43. Organization Rulesets~~ DONE

Implemented: `OrgRuleset extends Ruleset` in the schema — `Ruleset` became
`open` for it, which turns `Repository.rulesets` into
`Map<String, ? extends Ruleset>` on the Java side — with the repository name
and property conditions only an organization ruleset has.
`OrgRulesetDriftGroup` is `RulesetDriftGroup` on the organization endpoints
plus those conditions; everything else is `RulesetComparison`'s.
`RulesetSourceType` gained `Enterprise`, and both sides drop enterprise
rulesets before comparing.

## ~~44. Code Security Configurations~~ DONE

Implemented: `org_code_security_configurations`. The sixteen
`enabled`/`disabled`/`not_set` toggles sit in one map on
`ActualCodeSecurityConfiguration`, keyed by wire name, and the group compares
them from a table. Three writes per configuration, each its own `DriftFix`:
settings (PATCH, or POST for a missing one), `defaultForNewRepos`
(`PUT .../defaults`, read from the defaults listing) and attachments
(`POST .../attach` with the ids resolved from the account's listing). Global
configurations are dropped; extra configurations and repositories attached
outside the config are reported and left alone.

## ~~45. Teams, Organization Members and Collaborators~~ DONE

Implemented: `org_teams`, `org_members` and `collaborators`. Teams are matched
by slug, their members read per role and fixed with one membership PUT per
missing login, and a `parent` slug is written as the id resolved from the
listing already read. Members come from two role listings and are fixed with
the membership PUT that also invites. Collaborators are the direct ones only;
the permission is read from GitHub's permission booleans, which spell
`triage` and `maintain` where `role_name` does not agree with the config's
vocabulary, and team access is written through the organization's team
endpoint — a repository under a personal account reports a `teamPermissions`
entry as a config error. Every kind of membership GitHub has that the config
does not list is reported and left in place.

## ~~46. Runner Groups and Actions Repository Selection~~ DONE

Implemented: `org_runner_groups`, matched by name, with settings on a PATCH
and the repository selection on its own PUT; the default group is never extra
and never deleted, every other undeclared group is. `ActionsPermissions`
gained `selectedRepositories`, read whenever GitHub answers
`enabled_repositories = "selected"`, compared when either side is `selected`
and written through `PUT /orgs/{org}/actions/permissions/repositories` — the
item the spec had deferred. `OrgActionsPermissionsDriftGroup` now receives the
repository id map the secrets group already did.

## ~~47. Push and Repository-Target Rulesets~~ DONE

Implemented: `RulesetTarget` gained `push` and `repository`. A push ruleset
is the same group on the same endpoints with no conditions: the schema
refuses ref patterns on one, `RulesetComparison.refName` returns null for
the two ref-less targets, and `RulesetRequest.conditions` is omitted when
null rather than sent as `{}`. The `repository` target is an organization
ruleset's — `Repository.rulesets` is a `Mapping<String, Ruleset(target !=
"repository")>` — and carries the repository conditions without a ref-name
one. The file rules a push ruleset takes were already in `Ruleset`; drifty
does not check which rules a target accepts, so a rejected one is a failed
fix with GitHub's message.

## ~~48. Code Security Configuration Sub-Options~~ DONE

Implemented: `codeScanningDefaultSetupOptions` (runner type and label) and
`secretScanningDelegatedBypassOptions` (reviewers with id, type and mode) on
`CodeSecurityConfiguration`, both nullable: a config that leaves one out
says nothing about it, the group neither compares it nor sends it, and
GitHub keeps what it has. `ActualCodeSecurityConfiguration` carries them
flattened — a null runner object reads as `not_set`, a reviewer without a
`mode` as `ALWAYS` — and the settings PATCH carries them beside the toggles.

## ~~49. The Remaining Code Security Sub-Options~~ DONE

Implemented: `CodeSecurityConfiguration` gains `codeScanningOptions`
(`allowAdvanced`) and `dependencyGraphAutosubmitLabeledRunners`.

The two are not the same shape, because GitHub does not return them the same
way. `code_scanning_options` is omitted on a configuration that never set it,
and answers `allow_advanced` as null within the object when it has one, so it
is a nullable object like `codeScanningDefaultSetupOptions`: compared and sent
only when the config sets it. `dependency_graph_autosubmit_action_options`
comes back on every configuration — GitHub's own provided ones included, which
never set it — so its single field is flat, defaulted to GitHub's `false`, and
compared and sent like any of the seventeen toggles.

`secret_scanning_extended_metadata` is left out: the spec has it on GET, POST
and PATCH but supplies no default, and every example response predates the
field. See FOLLOWUPS.md item 4. `code_security` and `secret_protection` are
left out for good: POST and PATCH accept them, GET returns neither, and
`advancedSecurity` already carries the same split.

## ~~50. Export~~ DONE

Implemented: `drifty --export <login>...` reads every group `RepositoryChecker`
and `OrganizationChecker` know how to fetch, with everything managed, and
writes a Pkl file amending `config/drifty.pkl` that carries only the settings
that differ from the schema's defaults — `SchemaDefaults` evaluates the
schema for those, the same way `testsupport.Desired` does for tests.
`PklNode`/`PklWriter` are a small node tree and renderer shared by every
section exporter, so a section is a list of field comparisons rather than a
string builder. A group whose own read fails (`FetchFailures.collecting()`)
is named in that entry's `managed` block and gets a `//` note where its
section would sit, instead of aborting the account, and a repository whose own
details cannot be read becomes a note in the listing instead of losing every
other repository's export. A check-only
setting (`visibility`, ten organization settings) is exported as a field with
a note beside it — the field keeps the file round-tripping, the note says
`--fix` will never act on it. `ExportRoundTripTest` loads the exported file
back through the real checker against the same fixture the export was taken
from and asserts zero drift; `SchemaCoverageTest` fails the build when a
schema field has no corresponding exporter line, which is what keeps that
round trip meaningful rather than two omissions agreeing with each other.
Secret values are never returned by GitHub and are exported as a note instead
of a field.

## ~~51. Reject unknown arguments and print a usage~~ DONE

Implemented: `GitHubCheck.unknownArguments` walks the argument list against
`BOOLEAN_FLAGS`, `VALUE_OPTIONS` and `EXPORT` and returns everything drifty
would otherwise have discarded; `main` refuses the invocation with exit 1 when
that list is not empty, before any request is sent. `usage()` is the text
`--help` (and `-h`) prints, ahead of the token check so it works with no
environment at all, and `usage_namesEveryArgumentDriftyAccepts` reads the
accept-lists rather than a hand-kept copy, so an argument cannot be accepted
without being documented. `handledVersion` moved from "the single argument is
`--version`" to "the arguments contain `--version`", since an argument beside
it is now refused rather than silently turning the run into a check.

Issue #140: an unrecognised argument fell through to a plain check run, so
`--fixx` checked and exited 1 on drift (reading as a fix with nothing to do),
`--confg other.pkl` read the very file the flag was meant to replace, and
`--exports ArloL` checked instead of exporting and dropped the login too. An
option still swallows whatever follows it — `--config --fix` names a config
file called `--fix` — because `optionValue` reads it that way and the rest of
the run uses that value.

## ~~52. Leave an unreadable group unmanaged in the exported file~~ DONE

Implemented: `AccountExporter.addUnmanagedGroups` turns the failures
`FetchFailures.collecting()` gathered into the entry's own `managed` block —
`managed { groups { "custom_properties" } }`, with `mode` omitted because its
schema default of `all_except` already reads a named group as excluded. It
runs first for an organization and right after `name` for a repository, which
is where the schema orders the field, and before the archived branch:
`RepositoryChecker.fetchState` reads a group for an archived repository too.
The per-group notes stay where they were — they say *why* a group is
unmanaged, beside the section it would have filled; `managed` says *that* it
is, in the one place a later run reads.

Issue #136: the note was the only record, and a `//` comment is the one form a
later run cannot act on, so the file the README points people at as their
starting config was one drifty could not check — `drifty --export ArloL`
noted `custom_properties` on all 102 entries and the next `drifty` run died on
the first of those requests. `ExportRoundTripTest`'s
`aGroupTheExportCouldNotReadIsLeftUnmanagedSoTheFileStillChecks` 403s one
organization group and one repository group, keeps both 403s stubbed for the
check that follows, and asserts the round trip is clean — so it passes because
the requests are no longer sent, not because the endpoints recovered. The
stderr summary says "left unmanaged in the file" rather than "noted in the
file instead" for the same reason.

## ~~53. Export a file `pkl format` leaves alone~~ DONE

Implemented: `PklWriter` writes an empty body as `{}` on the line that opens
it, and moves a scalar assignment's value onto its own line once the
assignment would pass `LINE_WIDTH` — 100 columns, the width `pkl format`
0.32.1 breaks at, measured against the real formatter rather than assumed.
`NOTE_WIDTH` stays at 80 and is only where comments wrap, which is drifty's
own readability choice: the formatter never rewraps a comment. Those two
shapes are the whole difference; a block header, a listing element and a
comment are left where they are however long they get.

Issue #138: the README points `--export` at people adopting drifty for an
account that already exists, and drifty-arlol's `ci.yaml` runs `pkl format
--diff-name-only drifty.pkl`, so the exported file failed a formatting check
on its first CI run — 85 diff lines on a 4165-line export of a
102-repository account, all of them one of those two shapes.
`ExportRoundTripTest`'s repository fixture now carries a description long
enough to wrap, so the round trip also proves a wrapped assignment is still
the same string Pkl reads back.

## ~~54. Count the export's unreadable groups the way it lists them~~ DONE

Implemented: `ExportRunner.reportUnreadableGroups` counts the distinct group
names it prints, and names the number of failed reads behind them — `2
group(s) could not be read and are left unmanaged in the file (3 failed
reads): action_secrets, rulesets` — whenever that number is larger. Both
numbers survive, each saying what it counts; a single failure prints the line
it always did, since there is nothing to tell apart.

Issue #147: the count was `unreadableGroups.size()` against a `.distinct()`
list, so the two disagreed for every account whose repositories share a
permission gap — the common shape. A four-repository organization 403ing on
three groups per repository and two of its own reported `14 group(s) ...` above
five names, which reads left to right as nine names missing from the line. The
reads still say what the names cannot: one repository lacking a scope and the
whole token lacking it produce the same names.
