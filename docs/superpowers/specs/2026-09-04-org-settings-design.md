# Organization settings

drifty manages settings on the organizations that own the repositories it
already checks: the org profile and member policies, Actions permissions,
default workflow permissions, and org-level Actions secrets. Org rulesets and
code security configurations stay out of this slice.

## Repositories nest under their owner

`config/drifty.pkl` gets two top-level mappings keyed by login, and
`Repository.owner` goes away — the owner is the key of the block a repository
sits in.

```pkl
class User {
  repositories: Listing<Repository> = new {}
}

class Organization {
  managed: OrgManaged = new {}
  // org settings…
  actionsSecrets: Mapping<String, OrgSecret> = new {}
  repositories: Listing<Repository> = new {}
}

organizations: Mapping<String, Organization> = new {}
users: Mapping<String, User> = new {}
```

Two mappings rather than one keyed by kind: a personal account has no org
settings, and a separate `User` class makes "org settings on a personal
account" unrepresentable instead of settable-and-ignored. `config/ArloL.pkl`
becomes a `users` entry — its 42 repositories re-indent one level and drop
their `owner` lines.

Nesting also removes a failure the flat shape allowed: a repository could name
an owner that no other part of the config declared, and nothing checked.

Shared repository defaults stay ordinary Pkl `local` bindings that entries
amend. No per-owner defaults field.

## Settings

Org settings split by what the API can write. Everything in the first table is
on `PATCH /orgs/{org}`; defaults are GitHub's, so an org nobody has touched
reports no drift.

| Field | Wire name | Default |
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

`GET /orgs/{org}` returns ten more settings that `PATCH` accepts none of.
They are compared and reported, never sent — the null-`write` `Setting` shape
`visibility` already uses on the repository side.

| Field | Wire name | Default |
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

Three groups of `PATCH` fields are deliberately absent. `billing_email` is not
publicized and returns only to admins. `members_allowed_repository_creation_type`
is a legacy overlap of the three `members_can_create_*_repositories` booleans.
Every `*_enabled_for_new_repositories` security field carries an endpoint
closing-down notice in the OpenAPI spec, superseded by code security
configurations.

Defaults above come from GitHub's schema and its example response, which is
illustrative rather than authoritative. The first implementation step is a
read-only run against a real org: a setting that drifts without anyone having
changed it means the default here is wrong.

## Group names and the drift machinery

Four org groups get names in a new `OrgGroupName` typealias: `org_settings`,
`org_actions_permissions`, `org_workflow_permissions`, `org_action_secrets`.
The `org_` prefix survives even though repository and org names live in
separate unions, because `CheckResult.fixFailures` renders `name: path` across
both sections and a bare `settings.description` would not say which scope
failed.

`DriftGroup` becomes `DriftGroup<N extends Enum<N>>` and `ManagedGroups`
becomes `ManagedGroups<N>` built with a class token. Existing groups change
their `extends` clause and nothing else. The alternative — a parallel
`OrgDriftGroup` base — would copy `detect()`'s namespacing into a second file,
and that method is the single reason two groups cannot claim the same drift
path.

`OrgManaged` mirrors `Managed` with `Listing<OrgGroupName>`, so naming a
repository group in an org's `managed` block fails at config-eval.

`org_settings` follows `RepoSettingsDriftGroup` exactly: a `Setting` table
pairing each comparison with its builder call, a body built from the drifted
entries alone, and per-field re-send when a multi-field `PATCH` is rejected.
`members_can_create_internal_repositories` on a non-Enterprise org is the
`allow_forking` 422 again — a field GitHub rejects even when it already holds
the wanted value.

## Actual types and fetching

`OrganizationState(login, settings, actionsPermissions, workflowPermissions,
actionSecrets)` mirrors `RepositoryState` and holds no client type. New
`actual` records: `ActualOrganization`, `ActualOrgActionsPermissions`,
`ActualOrgSecret`. Org workflow permissions have the same wire shape as a
repository's, so `ActualWorkflowPermissions` is reused.

Each org group guards its own requests in the fetch, the same rule the
repository side follows: an unmanaged group that is only filtered out of the
comparison still sends its request, and an org someone else administers is
where those return 403.

`allowed_actions = "selected"` puts the allow-list on a second endpoint
(`/actions/permissions/selected-actions`), fetched only when either side
selects.

Secret visibility `"selected"` is names in config and repository IDs on the
wire. Desired names resolve against the repository listing the run already
performs for that owner, which carries `id`. Actual selections need
`GET /orgs/{org}/actions/secrets/{name}/repositories`, sent only for secrets
that are `selected` on one side or the other.

## Checkers and report

`OrgChecker` is renamed `RepositoryChecker`: with organizations in the picture
a class called `OrgChecker` that checks repositories misdirects every reader.
`OrganizationChecker` is new, and `printReport` moves to `Report`.

`GitHubCheck.main` lists each owner's repositories once and hands the listing
to both checkers, which is also what makes the org secret ID resolution free.
`RepositoryChecker` no longer lists repositories itself.

`CheckResult` becomes `CheckResult(List<Entry> orgs, List<Entry> repos)`.
`RepoCheckResult` is renamed `Entry` and used for both — an org record would
otherwise repeat the same seven fields and six factories. `Status` is
unchanged. Orgs never report `UNKNOWN`; enumerating every org the token can
see is not drift. `MISSING` on an org means the config names a login that 404s.
Org drift and org errors count toward exit code 1 exactly as repository drift
does.

```
=== Organizations ===
[DRIFT]   my-org:
            org_settings.description: want="..." got=""
            org_workflow_permissions.default_workflow_permissions: want=READ got=WRITE
  Would fix: org_settings, org_workflow_permissions

=== Repositories ===
[OK]      repo-a
```

## Secrets and state

Org secret values are keyed `org-<org>-<secret>` in `DRIFTY_GITHUB_SECRETS`.
The `org-` prefix keeps the key from colliding with a repository's
`<repo>-<secret>` when an org and a repository share a name — the map is flat
and nothing else separates them.

`DriftyState` gains `organizations` beside `repositories`, holding the same
`updated_at` and salted value hash per secret. The addition is additive, so
`version` stays 1 and existing state files load; a test pins that. `isEmpty()`
accounts for org records too, otherwise a run that records only org secrets
would decline to write the file.

Secret drift follows the existing table (missing, no recorded baseline, changed
outside drifty, config value changed, verified). Visibility is compared
alongside it. A visibility-only change still re-pushes the value, because the
`PUT` requires `encrypted_value`.

## Tests

A test per new group. `testsupport.Desired` gains `organization()` fed from a
new org entry in `desired-defaults.pkl`, so a new Pkl field needs no test-side
change. WireMock recording and playback cover the new endpoints.
`DriftPathNamespacingTest` gains an org-side completeness check;
`ActualStateBoundaryTest` covers `OrganizationState`.

Reachability metadata is regenerated by the documented procedure. New
`client` and `pkl` records are picked up by the ClassGraph augmentation.

## Deferred

Org rulesets, code security configurations, custom properties, webhooks,
teams and members, runner groups, Actions variables, and the org-level
immutable-releases settings. SPEC.md's "Future Considerations" keeps org
rulesets and loses org settings.
