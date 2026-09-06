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

- [ ] **Task 1: Environment reviewers, branch policies, extra environments**
  `Environment` fields; `ActualEnvironment` gains reviewers, preventSelfReview,
  branchPolicies; client gains deployment-branch-policy CRUD, `getUserId`,
  `getTeamId`; `EnvironmentConfigDriftGroup` compares and fixes; extras
  reported. Tests: group unit tests, client tests, fetch guard.

- [ ] **Task 2: Branch protection completions**
  `BranchProtection` fields; `ActualBranchProtection` fields; request record;
  comparison and builder. Tests: group unit tests.

- [ ] **Task 3: Ruleset completions and `RulesetComparison`**
  `Ruleset` fields (`pullRequest` replaces `requiredReviewCount`), new `Rule`
  subtypes, `ActualRuleset` fields, extract `RulesetComparison`. Tests:
  group unit tests, `ActualTypesTest`.

- [ ] **Task 4: Actions variables** (repo, environment, org)
  Client CRUD for all three; `ActualVariable`, `ActualOrgVariable`; three
  groups; state fields; fetch guards.

- [ ] **Task 5: Webhooks** (repo and org)
  `Webhook` class; client CRUD; `ActualWebhook`; `DriftyState.webhookSecrets`;
  two groups; preflight; fetch guards.

- [ ] **Task 6: Custom properties** (org definitions, repo values)

- [ ] **Task 7: Org rulesets** on top of `RulesetComparison`.

- [ ] **Task 8: Code security configurations**

- [ ] **Task 9: Teams, org members, collaborators**

- [ ] **Task 10: Runner groups and Actions repository selection**

- [ ] **Task 11: Docs, example config, metadata, full build, PR**
  SPEC.md tables, FEATURES.md entries 37+, CLAUDE.md notes, `config/example.pkl`
  exercising new sections, regenerate reachability metadata, `./mvnw verify`
  with the native test image, push, draft PR.
