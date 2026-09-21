# Request shape

- **`GET /orgs/{org}` is sent even when `org_settings` is unmanaged.** It is how
  `OrganizationStateReader.fetchState` learns the organization exists — a 404
  there
  is what makes the entry `MISSING` — and any member can read it. Every other
  org request is guarded by its group.
- **Two repository endpoints exist only under an organization.** `GET
  /repos/{owner}/{repo}/teams` and `GET /repos/{owner}/{repo}/properties/values`
  answer 404 on a user-owned repository whatever the token can do, so
  `RepositoryStateReader.fetchState` guards both on
  `ActualRepository.organizationOwned()` — which is why
  `ActualTypes.repository(details)` is built before them, not after.
  `CustomPropertiesDriftGroup` takes the same flag and reports the properties
  the config names as unfixable there instead of sending a PATCH that 404s,
  the way `CollaboratorsDriftGroup` does for team access. A new
  organization-only endpoint takes that shape too: the guard alone turns an
  abort into false drift and a fix that always fails.
- **`fetchState` fans out; two levels, never three.** `Fanout` starts every
  read that needs nothing at once, and only five wait: an environment's
  policies/secrets/variables, and `/teams`, `/properties/values` and `/pages`,
  which wait on `GET /repos/{owner}/{repo}` — the owner's type is what says
  whether to send the first two at all, and `has_pages` whether there is a site
  to ask about.
  Issuing them in series made the deepest single repository the
  floor on the whole run — 24 requests and 6.6s of a 9.5s check, with the
  semaphore idle. A group whose read waits on another group's read puts the
  chain back; `RepositoryCheckerRequestShapeTest` fails on a depth over 2 and
  on a per-repository request count that changed. Two things follow: join
  `details` first and outside every `failures.read`, so a repository whose own
  details failed does not report that failure under some group's name, and keep
  `FetchFailures.Collecting` **synchronized**, because one repository's groups
  now fail on different threads and they write that list concurrently.

- **One GraphQL query answers four groups, and it is written as four.**
  `GitHubClient.graphqlRepository` reads a repository's rulesets and their
  rules, its branch protections, its collaborators and its vulnerability-alerts
  flag in one request where REST needed six and two levels of waiting — 268 of
  a 101-repository check's 742 requests. Each of the four is its own aliased
  `repository` selection because GitHub nulls the whole `repository` object
  when one field inside it is forbidden: one alias each is what keeps a token
  that may not read branch protection from losing the rulesets too, and
  `GraphQlQuery.read` fails only the section whose error path names it. A fifth
  thing to read belongs in a fifth alias, not inside an existing one. The six
  REST reads it replaced are deleted rather than left unused, so there is one
  read path to reason about and nothing to call by accident.
- **The GraphQL answer is rewritten as the REST shape, not as new records.**
  `GraphQlShape` turns each node into the JSON `RulesetDetailsResponse`,
  `BranchProtectionResponse` and `CollaboratorResponse` already parse, so
  `ActualTypes` stays the one translator and every test over it still guards
  both routes. Verified against all 45 active repositories of one account: the
  `ActualRuleset` and `ActualBranchProtection` the two routes produce were
  identical for every one. Most of it is camelCase against snake_case and
  `SCREAMING_CASE` against the wire spelling; four things are not, and each has
  produced a wrong answer rather than an error when guessed —
  `lockAllowsFetchAndMerge` is `allow_fork_syncing`, a ruleset's status checks
  name their app as `integrationId` where a branch protection rule's use
  `app { databaseId }`, `MergeQueueParameters` spells two fields `...Minutes`
  that REST does not, and a bypass actor is a union plus three booleans where
  REST has an `actor_type` string.
- **The query asks `includeParents: false` and still reads `source`.** An
  organization's ruleset the repository endpoint cannot delete is not the
  repository's to reconcile, and `RepositoryStateReader.rulesets` filters it out
  by
  `sourceType` the way the REST path always did. Hardcoding `source_type` to
  `Repository` would mislabel one and produce a fix that always fails.
- **An organization's budget is three round trips, one more than a
  repository's.** `OrganizationStateReader.fetchState` shares the same
  `Fanout`,
  and the extra level is `GET /orgs/{org}`: it is how the checker learns the
  organization exists, so every group waits on it rather than firing two dozen
  requests at a login GitHub has never heard of. Below it each group's listing
  starts at once, and only a ruleset's rules, a team's two member listings, a
  configuration's repositories and defaults, the repositories behind a
  `selected` secret, variable or runner group, and the allow-list and
  repository selection behind `selected` Actions permissions wait — one round
  trip, never two. `OrganizationCheckerRequestShapeTest` fails on a fourth.
  The defaults listing is the one to be careful with: it looks independent of
  the configuration listing, but starting it eagerly spends a request on every
  organization that has no configuration and fails the group on a token that
  cannot read it, so it hangs off the listing beside the per-configuration
  reads instead.
- **The organization is checked before its repositories, and `--fix` needs
  that order.** Each phase is fanned out internally, but they are not run
  against each other: a repository cannot be granted access to a team that
  does not exist yet, cannot carry a value for a custom property whose
  definition does not exist yet, and a code security configuration attaches to
  repositories whose own security groups write the same settings. Overlapping
  the two phases is the obvious next speed-up and it is not available in fix
  mode; doing it for check mode alone would make the two modes send different
  request schedules, which is how a fix-only ordering bug stays invisible.
- **An archived repository costs the check nothing and the export one
  request.** When `desired.archived` is true `checkOne` compares
  `summary.archived()` off the account listing and never calls `fetchState`:
  `createDriftGroups` returns `ArchivedDriftGroup` and no other, and that group
  needs the one boolean the listing already carries. The export cannot follow —
  `RepositoryExporter.entry` writes an archived repository's settings down even
  though it compares none of them, and neither listing carries all of what it
  writes (below) — so
  `ExportRunner` still reads `GET /repos/{owner}/{repo}` under
  `RepositoryStateReader.ARCHIVED_ONLY`, and `collectMissingSecrets` narrows the
  same way so `--fix` is not aborted over a secret nothing will write. The
  check side keys on `desired.archived`, not on `actual`: a repository that is
  archived while the config wants it active still reads everything, because
  unarchiving is about to make all of it apply. The narrowing is not a
  `managed` declaration — the report's `unmanaged` list comes from the config's
  own block, or an `archived = true` repository's report would suddenly list
  every other group. Of one 101-repository account's 1152 requests, 343 went to
  groups nothing compared, and 51 more to details only one field of which was
  read.
- **A per-repository read the details response already answers is not sent.**
  `has_pages` is what `GET /repos/{owner}/{repo}/pages` answers with a 404 — 37
  of one account's 45 active repositories — so `fetchState` asks only when the
  details response says there is a site. It is read there rather than off the
  account listing, which the check no longer waits for.
  `security_and_analysis.dependabot_security_updates` on `GET
  /repos/{owner}/{repo}` is the same bit as `/automated-security-fixes`, which
  is why `SecurityFlags` has four members and not five; `paused` is the only
  field the endpoint carries that the details do not and nothing compares it.
  Before adding a read for a new group, diff what it returns against
  `schemas/repos/{owner}/{repo}/get/` and against the listing — one request per
  repository is ~0.3s of a run per 90 repositories.
- **An omitted section is not a section of falses.** GitHub leaves
  `security_and_analysis` out altogether on a repository whose account has none
  of the security features — every private repository of a Free-plan
  organization, under a token with full administration — and reading the absent
  section as `false` reported `automated_security_fixes` drift on four
  repositories that had it on, which no `--fix` could clear. So `fetchState`
  falls back to `/automated-security-fixes` exactly where the section is null:
  an account that carries it spends no request, and the fallback waits on the
  details response the way `/pages` does. A value taken off a shared response
  rather than its own endpoint is only as good as the accounts the section is
  sent for — verify against an account that has the feature turned off, not
  only the one it was measured on. The four secret-scanning flags read `false`
  there too and have no endpoint to fall back to.
- **The config says which repositories to check; the listing runs beside
  them.** `RepositoryChecker.check` submits every repository the config
  declares and does not want archived before it joins
  `GitHubClient.listUserReposAsync`, which is asking for the listing on its own
  virtual thread. Waiting for that listing was 596ms of a traced 3.09s fetch
  with fewer than ten requests in flight. The listing still answers the two
  questions only it can — what GitHub lists that the config does not declare
  (`UNKNOWN`), what it declares that GitHub does not list (`MISSING`) — plus
  `archived` for a repository the config wants archived. Two things follow.
  `fetchState` takes `archived` and `publicRepository` from the config rather
  than from a response, because reading either off `GET
  /repos/{owner}/{repo}` would put `/branches` behind it and each branch's
  protection behind that; a declared repository GitHub does not have spends its
  requests on 404s before the listing says so, which is the abnormal case. And
  a failing listing now fails inside `repoChecker.check`, which is why
  `GitHubCheck`'s user arm wraps both calls in one try — `checkOne` catches
  `GitHubApiException` itself, so nothing a repository does reaches that arm.
  The organization path keeps `listOrgRepos`: `OrganizationChecker.check`
  resolves a selected secret's repository ids against the whole listing, and it
  runs before the repositories anyway.
- **The repository listing is not a substitute for `GET
  /repos/{owner}/{repo}`.** Dropping the per-repository details request is the
  obvious way to save one request per repository and it does not work, and the
  two listings block it for opposite reasons. `GET /orgs/{org}/repos` is a
  `Minimal Repository`: 89 properties against the details response's 103, with
  `allow_*_merge`, `allow_auto_merge`, `allow_update_branch`,
  `merge_commit_*` and `squash_merge_commit_*` among the missing — most of what
  `RepoSettingsDriftGroup` compares, so every org-owned repository would report
  drift on all of them. `GET /user/repos` (95 properties) does carry the merge
  fields, and omits `security_and_analysis` instead, which nine groups read;
  only `automated_security_fixes` has an endpoint to fall back to, so the other
  seven secret-scanning flags would read `false` — the omitted-section bug
  above, on every repository instead of four. The organization listing has the
  section in the spec, but GitHub returned it on none of 101 repositories, so
  neither listing supplies it in practice. The check reads only
  `archived` off it now, and only for a repository the config wants archived:
  anything else a repository needs would put the listing back in front of every
  one of them.
- **`RepositoryChecker.entry` catches `GitHubApiException`.** It runs inside
  a virtual thread whose `Future.get()` nothing above `check` handles, so
  without that arm one repository's 403 ends the run for every repository after
  it — the shape of issue #135. `OrganizationChecker.check` has the same arm;
  keep both. The repository side catches `IOException` and
  `InterruptedException` besides, because `RepositoryStateReader.fetchState`
  declares them and the organization's does not — not a discrepancy to
  "fix" by adding unreachable arms to the organization side.

