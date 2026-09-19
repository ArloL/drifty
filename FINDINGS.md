# Where a check's time goes

Measured against the `ArloL` account (101 repositories, 45 of them active) with
the native macOS binary and a warm state file. Reproduce with the
instrumentation at the end before trusting any number here; GitHub's latency
moves by ±20% between runs, so single runs decide nothing.

A check was 3.34 s on 2026-09-19 and is 2.13 s after the three changes below.
The fetch is 2.05 s of that and is not CPU-bound anywhere.

## What bounds the run

Wall clock is `requests × latency ÷ permits`, and two of those three terms are
at their limit.

| term | measured | can it move |
| --- | --- | --- |
| latency | p50 249 ms, p95 450 ms | no — GitHub's, not drifty's |
| permits | 90 | no — GitHub refuses past ~100 |
| requests | 742, now 519 | yes — the only lever left |

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

**One GraphQL query per repository replaces six REST requests.** The rulesets
and their rules, the branch protections, the collaborators and the
vulnerability-alerts flag — 268 of 742 requests and both of a repository's
two-level chains, for one round trip of ~0.56 s against the ~0.25 s a REST read
costs. The answer is rewritten into the REST shape rather than into new
records, so `ActualTypes` stays the one translator; the two routes produced
identical `ActualRuleset` and `ActualBranchProtection` values for all 45 active
repositories. See `GraphQlQuery` and `GraphQlShape`.

The fetch window went from 3.09 s to 2.57 s on the first two changes, with 77%
of it at 90 requests in flight against a 2.31 s floor:

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

## What is left

Nothing with a measured number behind it. The floor is now 1.76 s of in-flight
time divided by 90 permits, the fetch runs ~0.3 s above it, and the three terms
that set it are where they were: GitHub's latency, GitHub's concurrency
ceiling, and 519 requests.

Of those 519, 360 are eight per active repository that GraphQL cannot answer —
of the `Repository` type's 141 fields none covers `security_and_analysis`,
`/immutable-releases`, `/private-vulnerability-reporting`,
`/code-scanning/default-setup`, `/hooks`, `/actions/secrets`,
`/actions/variables`, `/actions/permissions/workflow` or `/pages`.
`Environment` comes closest and still falls short: `DeploymentProtectionRule`
carries `preventSelfReview`, `reviewers`, `timeout` and `type`, but not the
deployment branch policies or their ids, which `--fix` needs to delete one.

Batching several repositories into one GraphQL query would take the floor to
1.62 s. It is not taken: it needs a batch shared across repositories, which
contradicts the property every other part of the fetch is built on — nothing a
repository is read for needs another repository — and which
`RepositoryCheckerRequestShapeTest` encodes. ~0.14 s is not worth that.

## Request count is also the headroom

`RequestPacer` bills a read one point against GitHub's 900 per minute, and a
check of this account costs 742. Two checks inside a minute exceed the budget —
which the replay experiments here ran into, drawing 403s on 663 of 742 requests
until they were spaced 200 s apart.

At 16.5 requests per active repository the pacer started delaying at about 55
active repositories, and above that wall clock is set by the budget rather than
by latency. The GraphQL query took the count to 10.5 and that wall to about 85;
200 active repositories is 2100 points, or 2.3 minutes of pacing whatever else
is optimised.

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
