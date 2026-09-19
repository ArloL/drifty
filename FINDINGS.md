# Where a check's time goes

Measured against the `ArloL` account (101 repositories, 45 of them active) with
the native macOS binary and a warm state file. Reproduce with the
instrumentation at the end before trusting any number here; GitHub's latency
moves by ±20% between runs, so single runs decide nothing.

A check was 3.34 s on 2026-09-19 and is 2.50 s after the two changes below. The
fetch is 2.44 s of that and is not CPU-bound anywhere.

## What bounds the run

Wall clock is `requests × latency ÷ permits`, and two of those three terms are
at their limit.

| term | measured | can it move |
| --- | --- | --- |
| latency | p50 249 ms, p95 450 ms | no — GitHub's, not drifty's |
| permits | 90 | no — GitHub refuses past ~100 |
| requests | 742 | yes — the only lever left |

Latency is not the token or the client. The same `GET /repos/ArloL/drifty`
answers in 115 ms unauthenticated and 400 ms authenticated over a connection
whose round trip is 40 ms, and an OAuth token is exactly as slow as a
fine-grained PAT. A 304 costs what the 200 it replaces costs, so the response
cache buys rate-limit budget and no wall clock — 197.4 s of in-flight time warm
against 199.9 s cold.

The permit count is at the ceiling. Firing simultaneous authenticated requests
at one account: 120 all answered 200, 150 drew 11×403, 270 drew 135×403.
GitHub documents 100 concurrent requests.

## Landed

**The schema is answered from inside the binary.** Every exported config
`amends` an `https://raw.githubusercontent.com/...` URL and Pkl's cache holds
packages only, so a plain HTTPS module was fetched again on every run.
Evaluating the config took 0.21 s against the URL and 0.07 s against a local
copy; drifty's config phase went from 0.14 s to 0.03 s. Checks now also work
offline. See `BundledSchema`.

**The config says which repositories to check, not the listing.** Waiting for
`GET /user/repos` was one request with nothing else in flight — 596 ms of a
3.09 s fetch. `RepositoryChecker.check` now submits every declared,
non-archived repository before joining the listing, which runs on its own
thread and answers only what it alone can: `UNKNOWN`, `MISSING`, and `archived`
for a repository the config wants archived.

The fetch window is now 2.57 s with 77% of it at 90 requests in flight, against
a 2.31 s floor:

| in flight | before | after |
| --- | --- | --- |
| 90+ | 1788 ms (57.8%) | 1970 ms (76.6%) |
| 80–89 | 310 ms | 249 ms |
| under 10, at the head | 596 ms | 0 ms |
| under 10, at the tail | 195 ms | 152 ms |

## Measured and not worth doing

Each of these was tried against the live API rather than reasoned about.

| idea | result |
| --- | --- |
| Dispatch parent reads ahead of leaves | nothing there. Replaying the trace's durations through a scheduler gives 2597 ms for parents-first; the run already lands at 2573 ms, so the arbitrary order is already as good |
| A second HTTP/2 connection | nothing there at equal permits: two connections of 45 averaged 2617 ms against 2527 ms for one of 90, over two rounds each |
| More permits than 90 | 99 works and buys 8%, but 100 is GitHub's documented ceiling and 90 is the whole of the run's margin |
| One GraphQL query for the whole account | 6.5 s. The per-query floor is paid once but ~0.15 s per repository is not |
| Conditional requests, for time | a 304 costs what a 200 costs |
| Dropping `GET /repos/{owner}/{repo}` for the listing's copy | the listing is a `Minimal Repository` and omits most of what `RepoSettingsDriftGroup` compares |
| Skipping repositories whose `updated_at` has not moved | rulesets, secrets, webhooks, environments and collaborators do not move it |

## Left: one GraphQL query per repository

Request count is the only term left, and REST cannot go below one round trip
per setting. One GraphQL query answers what six REST requests answer today:

| REST request | per run | GraphQL field |
| --- | --- | --- |
| `/branches?protected=true` | 45 | `branchProtectionRules.matchingRefs` |
| `/branches/{b}/protection` | 44 | `branchProtectionRules` |
| `/rulesets` | 45 | `rulesets` |
| `/rulesets/{id}` | 44 | `rulesets.rules.parameters` |
| `/collaborators` | 45 | `collaborators(affiliation:DIRECT)` |
| `/vulnerability-alerts` | 45 | `hasVulnerabilityAlertsEnabled` |

That is 268 of 742 requests and both of the repository's two-level chains.
Every field was fetched successfully against all 45 repositories on
2026-09-19, and the `RuleParameters` union covers every rule type
`ActualRuleset` carries.

### What it is worth, and what that costs

Replaying the two request sets against the live API, two clean rounds each:

| condition | run 1 | run 2 | mean |
| --- | --- | --- | --- |
| 742 REST, 90 permits | 2458 ms | 2632 ms | 2545 ms |
| 742 REST, 99 permits | 2353 ms | 2332 ms | 2343 ms |
| 474 REST + 15 GraphQL, one connection, 90 permits | 2249 ms | 2704 ms | 2477 ms |
| 474 REST at 85 permits + 15 GraphQL at 15 | 1775 ms | 1942 ms | 1859 ms |

Read those against the row above them, not against drifty: they are
unconditional requests issued flat, and the last condition carries 100 permits
where the others carry 90. Against the same REST set at 100 permits, which
averaged 2223 ms over two rounds, GraphQL is worth ~16% — not the 26% the first
and last rows suggest.

Projected onto drifty as it now stands, from in-flight seconds ÷ 90:

| shape | in-flight | floor | check |
| --- | --- | --- | --- |
| today | 207.7 s | 2.31 s | 2.50 s |
| one query per repository | 159 s | 1.77 s | ~2.1 s |
| one query per three repositories | 145 s | 1.62 s | ~1.95 s |

The three-repository row needs a batch shared across repositories, which
contradicts the property every other part of the fetch is built on — nothing a
repository is read for needs another repository — and which
`RepositoryCheckerRequestShapeTest` encodes. Take the per-repository row unless
that property is deliberately given up.

### Why this is not a mechanical port

`ActualRuleset` carries 35 fields across 20 rule types and `ActualTypes.ruleset`
maps them from the REST shape. The GraphQL shape differs in four places that a
translation has to get right, and a wrong one produces false drift rather than
an error:

- `branchProtectionRules` is keyed by pattern where drifty's model is keyed by
  branch. `matchingRefs` on each rule reproduces what `/branches?protected=true`
  answers; without it a `*` pattern maps to no branch.
- `bypassActors` carries `actor` as a union plus `repositoryRoleDatabaseId`,
  `organizationAdmin` and `deployKey`, where REST carries `actor_type` and
  `actor_id`.
- A ruleset's status checks are `requiredStatusChecks { context integrationId }`
  where a branch protection rule's are
  `requiredStatusChecks { context app { databaseId } }`.
- `lockAllowsFetchAndMerge` is REST's `allow_fork_syncing`, and
  `MergeQueueParameters` spells two fields `...Minutes` that REST does not.

The `ArloL` account exercises perhaps a third of the rule types, so a live run
reporting no drift is necessary and not sufficient. What makes this safe is a
fixture per rule type asserting the GraphQL mapping produces the same
`ActualRuleset` as the REST fixture beside it, which `ActualTypesTest` and
`RulesetDriftGroupTest` already have the shape for.

### What stays on REST

Of the `Repository` type's 141 fields, none covers `security_and_analysis`,
`/immutable-releases`, `/private-vulnerability-reporting`,
`/code-scanning/default-setup`, `/hooks`, `/actions/secrets`,
`/actions/variables`, `/actions/permissions/workflow` or `/pages`.
`Environment` comes closest and still falls short: `DeploymentProtectionRule`
carries `preventSelfReview`, `reviewers`, `timeout` and `type`, but not the
deployment branch policies or their ids, which `--fix` needs to delete one.

`RequestPacer.pointsFor` bills a POST at five points, and a GraphQL read is a
POST that is not a write; billed as one it paces the run against a budget it is
not spending. The GraphQL client also wants its own `HttpClient`: the split
condition above was the fastest, and mixing 10–25 KB GraphQL responses into the
connection carrying 90 conditional GETs is the one explanation left for it. Its
two permit pools have to sum to no more than the 90 the run has now.

## Request count is also the headroom

`RequestPacer` bills a read one point against GitHub's 900 per minute, and a
check of this account costs 742. Two checks inside a minute exceed the budget —
which the replay experiments here ran into, drawing 403s on 663 of 742 requests
until they were spaced 200 s apart.

At 16.5 requests per active repository the pacer starts delaying at about 55
active repositories, and above that wall clock is set by the budget rather than
by latency: 200 active repositories is 3280 points, or 3.6 minutes of pacing
whatever else is optimised. The GraphQL work takes the per-repository count to
about 10.5 and moves that wall to ~85.

## Reproducing the measurements

Add a `Phase.mark(...)` call at each phase boundary of `GitHubCheck.main`, and
wrap the `http.send` in `GitHubClient.sendBounded` so every request records
method, URI, status, submit time, time waiting for a permit, time in flight and
body size to the file named by an environment variable. Build with `./mvnw
-DskipTests package` and run the binary from `~/Developer/drifty-arlol`, which
supplies the token through `mise.local.toml`.

Two things are worth deriving from that trace. The instantaneous concurrency
distribution — sort the start and end events, walk them, and bucket the time
spent at each level — is what says whether the run is saturated and where it is
not. The sum of in-flight milliseconds divided by the permit count is the floor
no scheduling change can go below.

Replay experiments against the live API cost a point per request and share the
900-per-minute budget with everything else using the token. Space them 200
seconds apart or the numbers are 403s.
