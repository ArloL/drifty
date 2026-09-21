# Comparing and fixing a group

- **Eight groups PATCH the same `/repos/{owner}/{repo}` resource.** Each
  request carries only its own fields because `RepositoryUpdateRequest` is
  all nullable wrappers under `NON_NULL`; keep it that way.
- **A settings group is a table of rows; `SettingTable` is everything else.**
  Each row pairs a comparison with the builder call that writes it, and the
  PATCH carries the drifted rows alone. Building the body from `desired`
  instead is the shape to avoid: it sent `allow_forking` on every org-owned
  repository, and an org with `members_can_fork_private_repositories` off
  answers that field with a 422 even when it already holds the wanted value,
  failing a description change over a setting that had not drifted.
  `members_can_create_internal_repositories` is the org-side case — a 422 on
  any organization outside Enterprise. A row with a null `write` is reported
  but never sent: `visibility` on the repository side per SPEC.md, and ten
  organization settings `GET /orgs/{org}` returns that the PATCH accepts none
  of. A third settings group supplies rows and a send, and inherits the rest;
  do not copy the machinery again.
  `everyWritableSettingWritesTheFieldItCompared` exists on both group tests and
  reads its cases out of the table, so a new row needs no test change and a row
  whose builder call writes a different setting than it compared fails it.
- **A rejected PATCH is not a failed PATCH.** GitHub applies the fields it
  accepts and rejects the rest, so a 422 attributes to no field. When a
  multi-field request fails, `SettingTable` re-sends each field on its own and
  reports only the ones that fail again. Collapsing that back to
  "the request threw, so nothing was fixed" is what made a run report every
  setting unfixed after GitHub had already changed most of them.
- **The team POST and PATCH need different bodies.** `TeamRequest` serializes
  `parent_team_id` even when null because that is how the PATCH removes a
  parent, but `POST /orgs/{org}/teams` answers 422 to that null instead of
  reading it as "no parent", so no top-level team could be created.
  `TeamCreateRequest` is the same fields with the field omitted when null, and
  `createTeam` takes only that record. Do not collapse the two back into one.
- **`github-pages` is GitHub's environment, not the config's.** GitHub creates
  it the moment a Pages site is published, so
  `EnvironmentConfigDriftGroup` skips it in the extra-environment scan on a
  repository whose config declares `pages` — otherwise every repository that
  asks for Pages drifts against that same config (issue #157). The skip is
  conditional on `desired.pages != null` rather than unconditional so the
  environment a turned-off site left behind is still reported, and it does not
  consult `ManagedGroups`: the declaration is what says the site is wanted. An
  environment the config declares is compared like any other, `github-pages`
  included.
- **`--fix` deletes only what the config can recreate.** Rulesets, branch
  protections, webhooks, deployment branch policies and non-default runner
  groups are deleted when extra; secrets, variables, environments, custom
  property definitions, code security configurations and every kind of
  membership are reported with a reason and left alone. A new keyed group
  picks one of the two and says why in its class comment; SPEC.md's "What
  `--fix` Deletes" lists both sets.
- **A fix has to converge, and only one test asks whether it does.** Every
  other fix test stops at the request — a drifted field produced a PUT, and
  sometimes the PUT carried the field. A field the comparison reports but the
  request never writes passes all of them: drifty reports the drift, sends a
  request GitHub accepts, prints the setting FIXED and reports it again on the
  next run, forever, with no error anywhere. `RulesetFixConvergenceTest` reads
  the captured request body back as the response it shares a shape with — a
  ruleset's POST, PUT and GET carry the same document, which is why
  `RulesetRequest` and `RulesetDetailsResponse` are near-mirrors — and asserts
  the comparison is then empty. It takes its cases from
  `RulesetDriftGroupTest`'s own per-field table so the two directions cannot
  fall out of step, plus one maximal ruleset for the ten fields that table
  does not reach; `everyFieldDiffersFromTheSchemaDefault` is what stops that
  fixture quietly decaying when a schema field is added. What it does not
  model is GitHub rejecting or normalising a value, which is deliberate:
  which rules a target accepts is not checked anywhere.
- **A group whose write replaces rather than patches wants that test twice
  over.** `PUT /repos/{owner}/{repo}/branches/{branch}/protection` replaces
  the whole protection, so a field `BranchProtectionDriftGroup` compares but
  does not put is not merely left drifted — fixing any other field on that
  branch turns it off, and the run reports FIXED. That is a `--fix` that
  removes protection a config asked for.
  `BranchProtectionFixConvergenceTest` runs the maximal config against both an
  unprotected branch and one protected at every default, for that reason.
  Here the request and the response are not the same document, so the test
  carries a translation: `enforce_admins` goes out a boolean and comes back
  `{"enabled": true}`, an actor goes out a login and comes back an object
  carrying one. Keep that translation to the wire shape and nothing else — a
  simulation that started reproducing the group's own semantics would agree
  with a bug rather than find it.
- **An item drifty reports but never writes is `DriftFix.reported`.** The
  `Would fix:` preview names the groups holding at least one fix that can act,
  so a `new DriftFix(item, () -> FixResult.unfixed(...))` built by hand is
  counted as writable and offers a run that would do nothing — check mode
  printed `Would fix: collaborators` beside an extra collaborator `--fix`
  leaves in place (issue #156). `DriftGroup.detect` rebuilds every fix to
  namespace its paths and has to carry the flag through; dropping it there
  silently makes every group actionable again.
- **The account that owns a repository is not one of its collaborators.**
  `affiliation=direct` lists a personal account's owner, as admin, and
  `ActualTypes.collaborators` drops it — the grant is the ownership and no PUT
  or DELETE touches it. Keeping it made every personal repository drift on an
  extra collaborator and wrote the owner into every exported repository.
  `CollaboratorsDriftGroup` reports a config that names the owner instead of
  sending a PUT GitHub answers 422, the way it does for a team on a personal
  repository.
- **Enterprise-owned entities never reach a group.** The checker drops
  rulesets whose `source_type` is `Enterprise`, custom properties whose
  `source_type` is `enterprise`, teams whose `type` is `enterprise` and code
  security configurations whose `target_type` is `global` before building
  the state, the way `RepositoryStateReader.rulesets` drops organization
  rulesets. Reporting them as extra produces a fix that always fails.
- **`Ruleset` is `open` so `OrgRuleset` can extend it.** The generated
  `Repository.rulesets` is therefore `Map<String, ? extends Ruleset>`; a
  method taking the repository's rulesets declares that wildcard, and
  `RulesetComparison` takes a `Drifty.Ruleset` so both groups share it.
- **A `push` or `repository` ruleset has no ref-name condition.**
  `RulesetComparison.refName` returns null for those targets and the request
  omits the condition (`RulesetRequest.conditions` is `NON_NULL`, since a
  repository push ruleset has no conditions object at all). The schema
  refuses `includePatterns`/`excludePatterns` on them and refuses the
  `repository` target in `Repository.rulesets`, so neither case reaches a
  group. Which rules a target accepts is not checked anywhere: GitHub's 422
  is the report.
- **Code security option sub-objects are nullable, and null means unmanaged.**
  `codeScanningDefaultSetupOptions`, `codeScanningOptions` and
  `secretScanningDelegatedBypassOptions` are compared and sent only when the
  config sets them. Do not give them a default: GitHub picks a runner type
  itself when default setup is enabled, omits `code_scanning_options`
  entirely on a configuration that never set it, and a defaulted object would
  report drift on every configuration created with only a name.
  `dependencyGraphAutosubmitLabeledRunners` is the counter-example and is
  therefore flat, not a fourth object: GitHub returns
  `dependency_graph_autosubmit_action_options` on every configuration, its own
  included, so the field is compared and sent like any of the seventeen
  toggles. Which shape a new sub-option takes is decided by whether GitHub
  returns the object unconditionally, not by it being nested on the wire.
- **A drifted entry with several endpoints gets one `DriftFix` per
  endpoint.** `OrgActionsPermissionsDriftGroup` (policy, allow-list,
  repository selection), `OrgCodeSecurityConfigurationsDriftGroup` (settings,
  defaults, attachments), `OrgRunnerGroupsDriftGroup` (settings, selection)
  and `OrgTeamsDriftGroup` (settings, members, maintainers) all do this so a
  rejected write is not reported as having failed the others. A missing entry
  is the exception: its POST returns the id the other writes need, so one fix
  does all of them.
- **Names are resolved to ids at fix time, never at check time.** Repository
  names resolve through the id map the checker builds from the account's
  listing (`repositoryIds`); a name that is not in it fails the whole fix
  rather than writing a shorter list and reporting success. Logins and team
  slugs the environment group needs resolve with one request each, only when
  a fix runs.

