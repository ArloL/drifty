# Remaining features

drifty manages everything GitHub exposes as repository or organization
configuration that an operator would want held to a declared state. This slice
closes the gaps between SPEC.md and the code, and takes every item off the
spec's Future Considerations list except GraphQL reads and repository
lifecycle.

Fourteen new drift groups and six extensions to existing ones. Every group
follows the shape the codebase already has: a Pkl class in `config/drifty.pkl`,
an `actual/*` record translated by `ActualTypes`, a `DriftGroup` that compares
and fixes, a guard in the checker's `fetchState`, and a name in the `GroupName`
or `OrgGroupName` union.

## What drifty deletes

The rule for entries GitHub has and the config does not declare:

- **Deleted by `--fix`** when the config can recreate everything the deletion
  discards: rulesets and branch protections (already), webhooks, runner groups
  other than the default, deployment branch policies.
- **Reported and left alone** when the deletion discards something the config
  never held: secrets and variables (values), environments (their secrets and
  history), custom property definitions (their values on every repository),
  code security configurations (their attachments), and every kind of
  membership — org members, team members, collaborators. Each is reported as
  `extra` with the reason `--fix` gives for not touching it.

## Extensions to existing groups

### `environment_config`

`Environment` gains:

```pkl
class Environment {
  secrets: Listing<String> = new {}
  variables: Mapping<String, String> = new {}
  waitTimer: Int = 0
  preventSelfReview: Boolean = false
  reviewerUsers: Listing<String> = new {}   // logins
  reviewerTeams: Listing<String> = new {}   // slugs
  protectedBranches: Boolean = false
  customBranchPolicies: Boolean = false
  deploymentBranchPatterns: Listing<String> = new {}
  deploymentTagPatterns: Listing<String> = new {}
}
```

Reviewers are compared as the set of `User:<login>` and `Team:<slug>` strings.
GitHub's PUT wants numeric ids, so the fix resolves each login through
`GET /users/{login}` and each slug through `GET /orgs/{org}/teams/{slug}` at
fix time — the reads are only sent when a fix runs, and a name that does not
resolve fails the environment's fix with that name in the reason. A repository
under a personal account cannot name a team; that is reported, not resolved.

Deployment branch policies are compared as the set of `branch:<pattern>` and
`tag:<pattern>` strings and only when `customBranchPolicies` is true on either
side, since GitHub returns 404 for the listing otherwise. Each policy is its own
`DriftFix`: a missing one is created, an extra one deleted. The listing costs
one request per environment with custom policies.

Environments on GitHub that the config does not declare are reported as
`extra` and left alone, as SPEC.md already says.

### `branch_protection`

`BranchProtection` gains the rest of the PUT body:

```pkl
  strictStatusChecks: Boolean = false
  allowDeletions: Boolean = false
  blockCreations: Boolean = false
  lockBranch: Boolean = false
  allowForkSyncing: Boolean = false
  dismissalUsers: Listing<String> = new {}
  dismissalTeams: Listing<String> = new {}
  dismissalApps: Listing<String> = new {}
  bypassPullRequestUsers: Listing<String> = new {}
  bypassPullRequestTeams: Listing<String> = new {}
  bypassPullRequestApps: Listing<String> = new {}
```

Dismissal restrictions and bypass allowances are sub-objects of
`required_pull_request_reviews`; naming any of them counts as wanting that
section. `strictStatusChecks` replaces the `false` the comparison and the
request body had hardcoded.

### `rulesets`

`Ruleset` gains the target, the exclude list, enforcement, the full
`pull_request` parameters and the rule types it lacked:

```pkl
class PullRequestRule {
  requiredApprovingReviewCount: Int = 0
  dismissStaleReviewsOnPush: Boolean = false
  requireCodeOwnerReview: Boolean = false
  requireLastPushApproval: Boolean = false
  requiredReviewThreadResolution: Boolean = false
  allowedMergeMethods: Listing<MergeMethod> = new {}
}

class MergeQueueRule {
  checkResponseTimeoutMinutes: Int = 60
  groupingStrategy: "ALLGREEN" | "HEADGREEN" = "ALLGREEN"
  maxEntriesToBuild: Int = 5
  maxEntriesToMerge: Int = 5
  mergeMethod: "MERGE" | "SQUASH" | "REBASE" = "MERGE"
  minEntriesToMerge: Int = 1
  minEntriesToMergeWaitMinutes: Int = 5
}

class WorkflowRule {
  path: String
  repositoryId: Int
  ref: String?
}

class Ruleset {
  target: RulesetTarget = "branch"          // "branch" | "tag"
  enforcement: RulesetEnforcement = "active" // "active" | "evaluate" | "disabled"
  includePatterns / excludePatterns
  strictRequiredStatusChecks: Boolean = false
  pullRequest: PullRequestRule?              // replaces requiredReviewCount
  mergeQueue: MergeQueueRule?
  workflows: Listing<WorkflowRule> = new {}
  filePathRestrictions: Listing<String> = new {}
  maxFilePathLength: Int?
  fileExtensionRestrictions: Listing<String> = new {}
  maxFileSize: Int?
  // existing fields unchanged
}
```

`requiredReviewCount` goes away in favour of `pullRequest`, whose presence is
what says the rule exists. The comparison logic moves out of
`RulesetDriftGroup` into a package-private `RulesetComparison` — comparison and
request building over a `Drifty.Ruleset` and an `ActualRuleset` — because the
org group below needs exactly the same code with a different endpoint.

## New repository groups

| Group | Endpoint | Extras |
|---|---|---|
| `action_variables` | `/repos/{o}/{r}/actions/variables` | reported |
| `environment_variables` | `/repos/{o}/{r}/environments/{e}/variables` | reported |
| `webhooks` | `/repos/{o}/{r}/hooks` | deleted |
| `custom_properties` | `/repos/{o}/{r}/properties/values` | not applicable |
| `collaborators` | `/repos/{o}/{r}/collaborators`, `/repos/{o}/{r}/teams` | reported |

### Variables

```pkl
  actionsVariables: Mapping<String, String> = new {}
```

Plaintext, so the value is compared directly and no state file is involved. A
missing variable is POSTed, a drifted one PATCHed. Environment variables live
on `Environment.variables` and are fetched only when `environment_variables` is
managed, one listing per environment, the way environment secrets are.

### Webhooks

```pkl
class Webhook {
  url: String
  contentType: "json" | "form" = "form"
  insecureSsl: Boolean = false
  active: Boolean = true
  events: Listing<String> = new { "push" }
  /// Whether the hook carries a secret. The value comes from
  /// DRIFTY_GITHUB_SECRETS under `<repo>-webhook-<name>`.
  secret: Boolean = false
}

  webhooks: Mapping<String, Webhook> = new {}   // keyed by a name of your choosing
```

GitHub has no name for a hook — every repository hook is called `web` — so the
key is drifty's and the `url` is the identity that matches a config entry to a
hook on GitHub. Two config entries with the same URL are a config error
reported at check time; two GitHub hooks with the same URL match the first.

The secret is the one field GitHub never returns (the response says
`********` when one is set). Drift on it is detected the way secrets are: the
state file records the hook's `updated_at` and the salted hash of the value
drifty last pushed, under `webhook_secrets` beside `action_secrets` in both
the repository and the organization record. A hook whose config declares
`secret = true` but whose state has no record is `exists but has no recorded
baseline`; one whose `updated_at` moved is `changed outside drifty`; one whose
configured value hashes differently is `config value changed`. The state file
version stays 1: the new key is absent from older files and ignored by older
readers.

Every drift on a hook is fixed with one PATCH carrying the whole desired
config, secret included when declared, and then a GET to record the new
`updated_at`. `--fix` preflight counts webhook secrets among the values
`DRIFTY_GITHUB_SECRETS` must carry, subject to the group being managed.

### Custom property values

```pkl
  customProperties: Mapping<String, String> = new {}
  customMultiSelectProperties: Mapping<String, Listing<String>> = new {}
```

Two mappings because Pkl's codegen turns `String | Listing<String>` into
`Object`, which gains nothing over two typed fields. `true_false` properties
take the strings `"true"` and `"false"`, which is how GitHub stores them. Only
the properties the config names are compared: a property with a value on
GitHub that the config does not mention is not drift, since the org schema —
not the repository — decides which properties exist. The fix is one PATCH
listing the drifted properties.

### Collaborators

```pkl
typealias CollaboratorPermission = "pull" | "triage" | "push" | "maintain" | "admin"

  collaborators: Mapping<String, CollaboratorPermission> = new {}   // login → permission
  teamPermissions: Mapping<String, CollaboratorPermission> = new {} // team slug → permission
```

Direct collaborators only (`affiliation=direct`); a member who reaches the
repository through an org role or a team is not a collaborator to reconcile.
GitHub reports `role_name` as `read`/`write` and takes `pull`/`push` on the
PUT; the actual side is normalised to the config's vocabulary at the
`ActualTypes` boundary. Team access is written through
`PUT /orgs/{org}/teams/{slug}/repos/{owner}/{repo}`, so `teamPermissions` on a
repository under a personal account is a config error reported at check time.

A user who has been invited but has not accepted appears in neither listing,
so the entry is reported missing until they accept; the PUT that fixes it is
idempotent and does not resend the invitation.

## New organization groups

| Group | Endpoint | Extras |
|---|---|---|
| `org_action_variables` | `/orgs/{org}/actions/variables` | reported |
| `org_webhooks` | `/orgs/{org}/hooks` | deleted |
| `org_custom_properties` | `/orgs/{org}/properties/schema` | reported |
| `org_rulesets` | `/orgs/{org}/rulesets` | deleted |
| `org_code_security_configurations` | `/orgs/{org}/code-security/configurations` | reported |
| `org_teams` | `/orgs/{org}/teams` | reported |
| `org_members` | `/orgs/{org}/members`, `/orgs/{org}/memberships/{u}` | reported |
| `org_runner_groups` | `/orgs/{org}/actions/runner-groups` | deleted |

### Org variables

```pkl
class OrgVariable {
  value: String
  visibility: SecretVisibility = "private"
  selectedRepositories: Listing<String> = new {}
}
```

The org twin of `OrgSecret` with a value. Selected repositories are resolved to
ids through the listing the checker already has, exactly as org secrets do, and
are compared only when either side is `selected`.

### Org webhooks

The same `Webhook` class, keyed the same way, with the secret under
`org-<org>-webhook-<name>` and the state under the organization's
`webhook_secrets`.

### Custom property definitions

```pkl
class CustomProperty {
  valueType: "string" | "single_select" | "multi_select" | "true_false" | "url"
  required: Boolean = false
  defaultValue: String?
  defaultValues: Listing<String> = new {}   // multi_select only
  description: String?
  allowedValues: Listing<String> = new {}
  valuesEditableBy: "org_actors" | "org_and_repo_actors" = "org_actors"
}

  customProperties: Mapping<String, CustomProperty> = new {}
```

One PUT per drifted property. Properties whose `source_type` is `enterprise`
are not the organization's to change and are dropped before comparing, the way
org rulesets are dropped from a repository's list. Extra definitions are
reported and never deleted: deleting a definition discards its value on every
repository in the organization.

### Org rulesets

```pkl
class PropertyCondition {
  name: String
  propertyValues: Listing<String>
  source: "custom" | "system" = "custom"
}

class OrgRuleset extends Ruleset {
  repositoryNameInclude: Listing<String> = new {}
  repositoryNameExclude: Listing<String> = new {}
  repositoryNameProtected: Boolean = false
  repositoryPropertyInclude: Listing<PropertyCondition> = new {}
  repositoryPropertyExclude: Listing<PropertyCondition> = new {}
}

  rulesets: Mapping<String, OrgRuleset> = new {}
```

Every rule and condition the repository group handles, plus the repository
conditions only an org ruleset has. `repository_id` conditions are not offered:
the config names repositories, and a name condition covers the same ground
without an id lookup. `ActualRuleset` grows the fields for all of this, with
the repository conditions empty for a repository ruleset, and both groups share
`RulesetComparison`. Rulesets whose `source_type` is `Enterprise` are dropped
before comparing.

### Code security configurations

```pkl
typealias SecuritySetting = "enabled" | "disabled" | "not_set"

class CodeSecurityConfiguration {
  description: String = ""
  advancedSecurity: "enabled" | "disabled" | "code_security" | "secret_protection" = "disabled"
  dependencyGraph: SecuritySetting = "enabled"
  dependencyGraphAutosubmitAction: SecuritySetting = "disabled"
  dependabotAlerts: SecuritySetting = "disabled"
  dependabotSecurityUpdates: SecuritySetting = "disabled"
  dependabotDelegatedAlertDismissal: SecuritySetting = "disabled"
  codeScanningDefaultSetup: SecuritySetting = "disabled"
  codeScanningDelegatedAlertDismissal: SecuritySetting = "not_set"
  secretScanning: SecuritySetting = "disabled"
  secretScanningPushProtection: SecuritySetting = "disabled"
  secretScanningDelegatedBypass: SecuritySetting = "disabled"
  secretScanningValidityChecks: SecuritySetting = "disabled"
  secretScanningNonProviderPatterns: SecuritySetting = "disabled"
  secretScanningGenericSecrets: SecuritySetting = "disabled"
  secretScanningDelegatedAlertDismissal: SecuritySetting = "not_set"
  privateVulnerabilityReporting: SecuritySetting = "disabled"
  enforcement: "enforced" | "unenforced" = "enforced"
  /// Which new repositories get this configuration by default.
  defaultForNewRepos: "none" | "public" | "private_and_internal" | "all" = "none"
  /// Repositories this configuration is attached to. Missing ones are attached;
  /// repositories attached outside the config are reported, not detached.
  repositories: Listing<String> = new {}
}

  codeSecurityConfigurations: Mapping<String, CodeSecurityConfiguration> = new {}
```

Defaults are GitHub's POST defaults, so a configuration created with only a
name reports no drift. Only configurations whose `target_type` is
`organization` are compared; the GitHub-provided global ones are dropped. A
missing configuration is POSTed, a drifted one PATCHed; `defaultForNewRepos`
goes to `PUT .../{id}/defaults` and attachments to `POST .../{id}/attach` with
`scope = selected`, each as its own `DriftFix` so a rejected attachment is not
reported as a failed setting. Runner-label and bypass-reviewer sub-options are
not managed in this slice.

### Teams

```pkl
class Team {
  /// Display name; defaults to the slug the entry is keyed by.
  name: String?
  description: String = ""
  privacy: "closed" | "secret" = "closed"
  notificationSetting: "notifications_enabled" | "notifications_disabled" = "notifications_enabled"
  parent: String?                       // parent team slug
  members: Listing<String> = new {}     // logins with the member role
  maintainers: Listing<String> = new {} // logins with the maintainer role
}

  teams: Mapping<String, Team> = new {}   // keyed by slug
```

Teams are matched by slug. GitHub derives the slug from the name on creation,
so a key that is not the slug of its own name is created under one slug and
reported missing under the other on the next run; the schema documents this
and the fix for a missing team POSTs the name. Membership is compared per role
as two sets, read from `GET .../members?role=maintainer` and `?role=member`,
and fixed with `PUT .../memberships/{username}` carrying the role. Members on
GitHub that the config does not list are reported as extra and left in place.
Team repository access is managed from the repository side by
`collaborators`, so the org side does not read `.../repos`.

### Org members

```pkl
  members: Mapping<String, "admin" | "member"> = new {}   // login → role
```

Two listings, `?role=admin` and `?role=member`, give every member's role
without a request per user. A login missing from both is reported missing and
fixed with `PUT /orgs/{org}/memberships/{login}`, which invites the user; a
pending invitation is neither listing's, so the entry stays missing until
they accept. Extras are reported and never removed.

### Runner groups

```pkl
class RunnerGroup {
  visibility: "all" | "selected" | "private" = "all"
  selectedRepositories: Listing<String> = new {}
  allowsPublicRepositories: Boolean = false
  restrictedToWorkflows: Boolean = false
  selectedWorkflows: Listing<String> = new {}
}

  runnerGroups: Mapping<String, RunnerGroup> = new {}   // keyed by name
```

The group GitHub marks `default` is never extra and never deleted; naming it
in the config manages its settings. Selected repositories are resolved to ids
through the account's listing and written with `PUT .../repositories`, read
only for groups whose visibility is `selected`. Runner groups exist only on
paid plans, so the group is one an operator on a free organization excludes
through `managed`.

### Actions repository selection

`ActionsPermissions` gains `selectedRepositories: Listing<String>`, compared
when either side has `enabledRepositories = "selected"` and written with
`PUT /orgs/{org}/actions/permissions/repositories`. Read only when GitHub
answers `selected`, the way the allow-list already is. This closes the item
the spec deferred and gives `OrgActionsPermissionsDriftGroup` the repository
id map the secrets group already receives.

## Actual types and fetching

New records: `ActualWebhook`, `ActualVariable`, `ActualOrgVariable`,
`ActualCustomProperty`, `ActualCollaborators`, `ActualTeam`,
`ActualCodeSecurityConfiguration`, `ActualRunnerGroup`. `ActualEnvironment`
gains reviewers, `preventSelfReview` and branch policies;
`ActualBranchProtection.PullRequestReviews` gains dismissal restrictions and
bypass allowances, and the outer record the four booleans; `ActualRuleset`
gains target, enforcement, exclude patterns, the pull request parameters, the
new rule types and the repository conditions; `ActualOrgActionsPermissions`
gains `selectedRepositories`.

`RepositoryState` and `OrganizationState` gain a field per new group, null or
empty when the group is unmanaged, and `fetchState` guards every new request by
its group. `RepositoryCheckerFetchStateTest.unmanagedGroups_endpointsAreNeverRequested`
and `OrganizationCheckerTest` extend to the new endpoints.

`GitHubClient` gains one method per endpoint, in the existing style: a status
check, a `GitHubApiException` naming the entity, and `collectPaginatedArrayItems`
for listings. Request records are `@JsonInclude(NON_NULL)` so a PATCH carries
only what it sets.

## Secrets and state

`DriftyState.RepoState` and `OrgState` gain `webhookSecrets`, recorded and
looked up the way `actionSecrets` are; `isEmpty()` counts them.
`StateStoreTest` round-trips a webhook record so the native image carries the
field. `GitHubCheck.collectMissingSecrets` counts webhook secrets under the
`webhooks` and `org_webhooks` groups.

## Tests

Each new group gets a unit test over `Actual*` fixtures in the style of the
existing ones: no drift on matching state, one item per drifted setting,
missing and extra entries, and the fix's request body through WireMock where
the body is non-trivial. `GitHubClientTest` covers each new endpoint's
status handling. `DriftPathNamespacingTest`'s fixtures drift every new group so
the namespacing invariant covers them. `ActualStateBoundaryTest` needs no
change beyond the state constructors. `Desired` and `desired-defaults.pkl`
gain the new classes.

## Deferred

- Push and repository-target rulesets (different rule vocabularies).
- Code security configuration runner-label and bypass-reviewer options.
- Custom repository roles as collaborator permissions.
- Enterprise-owned entities of any kind: drifty drops them before comparing.
