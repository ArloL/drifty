# Remaining Features Implementation Plan

Implements `docs/superpowers/specs/2026-09-06-remaining-features-design.md`.
One commit per task; each task leaves `./mvnw -DskipNativeTests test` green.

## Global constraints

- Drift groups hold no client type but `GitHubClient`, `RepoRef` and enums
  (`ActualStateBoundaryTest`).
- Every new group name goes into `GroupName` or `OrgGroupName`
  (`DriftPathNamespacingTest`), and its fetch is guarded by `managed` in the
  checker (`RepositoryCheckerFetchStateTest`, `OrganizationCheckerTest`).
- Desired fixtures come from `desired-defaults.pkl` through `Desired`; new
  Pkl classes get an entry there and an accessor in `Desired`.
- Native metadata is regenerated once at the end with the tracing agent, not
  edited by hand.

## Tasks

- [x] **Task 1: Environment reviewers, branch policies, extra environments**
  `Environment` fields; `ActualEnvironment` gains reviewers, preventSelfReview,
  branchPolicies; client gains deployment-branch-policy CRUD, `getUserId`,
  `getTeamId`; `EnvironmentConfigDriftGroup` compares and fixes; extras
  reported. Tests: group unit tests, client tests, fetch guard.

- [x] **Task 2: Branch protection completions**
  `BranchProtection` fields; `ActualBranchProtection` fields; request record;
  comparison and builder. Tests: group unit tests.

- [x] **Task 3: Ruleset completions and `RulesetComparison`**
  `Ruleset` fields (`pullRequest` replaces `requiredReviewCount`), new `Rule`
  subtypes, `ActualRuleset` fields, extract `RulesetComparison`. Tests:
  group unit tests, `ActualTypesTest`.

- [x] **Task 4: Actions variables** (repo, environment, org)
  Client CRUD for all three; `ActualVariable`, `ActualOrgVariable`; three
  groups; state fields; fetch guards.

- [x] **Task 5: Webhooks** (repo and org)
  `Webhook` class; client CRUD; `ActualWebhook`; `DriftyState.webhookSecrets`;
  two groups; preflight; fetch guards.

- [x] **Task 6: Custom properties** (org definitions, repo values)

- [x] **Task 7: Org rulesets** on top of `RulesetComparison`.

- [ ] **Task 8: Code security configurations**

- [ ] **Task 9: Teams, org members, collaborators**

- [ ] **Task 10: Runner groups and Actions repository selection**

- [ ] **Task 11: Docs, example config, metadata, full build, PR**
  SPEC.md tables, FEATURES.md entries 37+, CLAUDE.md notes, `config/example.pkl`
  exercising new sections, regenerate reachability metadata, `./mvnw verify`
  with the native test image, push, draft PR.

## Handoff (2026-09-06)

Tasks 1–4 are done and committed, each with a green
`./mvnw -DskipNativeTests test`. Task 5 (webhooks) is half done in the last
commit; the branch compiles but three tests fail until the two new groups are
wired in:

- `DriftPathNamespacingTest.everyGroupNameConstantHasAGroup` and
  `everyOrgGroupNameConstantHasAGroup` — `webhooks` and `org_webhooks` are in
  the Pkl unions but no checker builds the groups yet.
- `OrganizationCheckerTest.matchingSettingsReportOk` — its expected unmanaged
  list needs `org_webhooks` added.

### What exists for webhooks

- `config/drifty.pkl`: `Webhook` class, `Repository.webhooks`,
  `Organization.webhooks`, group names `webhooks` / `org_webhooks`.
- Client: `WebhookResponse`, `WebhookRequest`, and `GitHubClient`
  `getRepoWebhooks` / `getOrgWebhooks` / `create…` / `update…` / `delete…`.
- `ActualWebhook` and `ActualTypes.webhook(WebhookResponse)`.
- `DriftyState`: `webhookSecrets` on `RepoState` and `OrgState`,
  `webhookSecretRecord` / `recordWebhookSecret` and the org twins, counted by
  `isEmpty()`.
- `WebhookReconciler` (shared logic), `WebhooksDriftGroup`,
  `OrgWebhooksDriftGroup`.
- `Desired.webhook(url)` and a `webhook` entry in `desired-defaults.pkl`.

### What is left for webhooks

1. `RepositoryState`: add `List<ActualWebhook> webhooks` to the canonical
   constructor (keep the 15-arg convenience constructor passing `List.of()`).
   `OrganizationState`: add `List<ActualWebhook> webhooks` the same way.
2. `RepositoryChecker.fetchState`: fetch
   `client.getRepoWebhooks(...)` only when `managed.manages(WEBHOOKS)`; map
   with `ActualTypes::webhook`. `createDriftGroups`: add
   `new WebhooksDriftGroup(desired.webhooks, actual.webhooks(), githubSecrets, state, client, ref)`.
   Same in `OrganizationChecker` with `ORG_WEBHOOKS` and
   `OrgWebhooksDriftGroup`.
3. `GitHubCheck.collectMissingSecrets`: for each repository whose `webhooks`
   group is managed, add `<repo>-webhook-<name>` for every hook with
   `secret = true`; for organizations under `org_webhooks`,
   `org-<org>-webhook-<name>`.
4. Tests: `WebhooksDriftGroupTest` / `OrgWebhooksDriftGroupTest` (matching
   hook → no drift; url match by name; content type / events / active /
   insecure_ssl drift; secret missing baseline / changed / value changed;
   duplicate url in config is an unfixable item; extra hook deleted; fix
   PATCH body with `insecure_ssl` `"0"`/`"1"` and `secret` only when
   declared); `GitHubClientTest` for the eight endpoints; `StateStoreTest`
   round-trip of a webhook record (the native image needs it traced);
   `DriftyStateTest.isEmpty` after a webhook record; `GitHubCheckTest`
   preflight keys; fetch guards in `RepositoryCheckerFetchStateTest` and
   `OrganizationCheckerTest`; stub `/repos/{o}/{r}/hooks` in
   `RepositoryCheckerCheckTest.stubRepoSubResources` and `/orgs/my-org/hooks`
   in `OrganizationCheckerTest.fixPreviewNamesOnlyTheGroupThatDrifted`;
   drift a webhook in both `DriftPathNamespacingTest` fixtures.

### Recurring per-group checklist (learned in Tasks 1–4)

Every new group touches: the Pkl union, `desired-defaults.pkl` + `Desired`,
a client record and methods, an `actual/*` record and `ActualTypes`, the
state record (canonical constructor + convenience constructor), the checker's
fetch guard and `createDriftGroups`, the unmanaged list in
`OrganizationCheckerTest.matchingSettingsReportOk`, the endpoint stubs in
`RepositoryCheckerCheckTest.stubRepoSubResources` and
`OrganizationCheckerTest.fixPreviewNamesOnlyTheGroupThatDrifted`, and the
fixtures in `DriftPathNamespacingTest`. The build auto-formats Java sources
(spring-javaformat), so expect formatting-only diffs after a test run.

### Not started

Tasks 6–11: custom properties, org rulesets (build on `RulesetComparison`
and `RulesetRequest.Conditions.repositoryName` / `repositoryProperty`; the
`Drifty.OrgRuleset extends Ruleset` class from the spec), code security
configurations, teams / members / collaborators, runner groups and Actions
repository selection, then docs (SPEC.md tables, FEATURES.md entries 37+,
CLAUDE.md), `config/example.pkl`, reachability metadata regeneration
(`./mvnw test -Dagent=true && ./mvnw test-compile && ./mvnw exec:java@reachability-metadata`),
and a full `./mvnw verify` with the native test image before merging. The
downloaded `schemas/` directory (gitignored) holds every endpoint's shape;
`python3 download-schemas.py --filter '/repos/{owner}/{repo}' --filter '/orgs/{org}'`
recreates it.
