# Request scheduling

- **`GitHubClient` bounds its own in-flight requests, not the checkers.** The
  checkers start one virtual thread per repository and `fetchState` starts more
  under each, so nothing above the client knows how many requests are in
  flight; GitHub's single HTTP/2 connection caps concurrent streams at 100 and
  the JDK client throws `IOException: too many concurrent streams` rather than
  queueing, which killed a run over ~100 repositories before it checked
  anything (issue #137). The semaphore sits in `sendBounded` because every
  caller shares that one connection — a bound around the per-repository threads
  would leave the export path and any future parallelism unguarded.
  `sendBounded` declares the checked exceptions rather than catching them so
  `sendRequest` keeps the one pair of arms, and the permit is gone before any
  rate-limit pause, so a thread parked until the reset is not holding a stream.
  `GitHubClientConcurrencyTest` fails if more requests overlap than the limit.
- **Drifty asks for the whole of GitHub's hundred concurrent requests.** A
  token given to drifty is normally drifty's alone, so
  `MAX_CONCURRENT_REQUESTS` is the documented limit rather than a margin under
  it: interleaved against each other three times on the 101-repository `ArloL`
  account, ninety permits averaged 2.23s and a hundred 1.97s with nothing
  refused. A token drifty shares is what wants the margin back, and
  `--max-concurrent-requests` is how it is asked for. `GitHubCheck` refuses a
  value past `CONCURRENCY_CEILING` rather than letting GitHub do it.
- **The connection is opened before there is anything to send on it.** A check
  starts a hundred requests at once and every one of them waits out DNS, TCP and
  TLS: the first wave answered in 413ms against the 237ms the rest of the run
  saw. `GitHubClient.warmUp` sends an unauthenticated `HEAD /` — the pool is
  keyed by host, not by credentials — and `main` calls it before evaluating
  the config rather than after, so the handshake and the Pkl evaluation
  overlap. Worth ~20ms against a config that evaluates in 30ms, and more
  against a larger one; the ceiling on it is however much work there is to do
  before the first request.
- **A rate limit is not a failed request.** `sendRequest` re-sends through a 403
  or 429 that carries `Retry-After` or `X-RateLimit-Remaining: 0`, and pauses
  (without re-sending) after a good response that spent the last of the budget.
  A 403 with neither is a token without a scope and has to keep reaching
  `FetchFailures` as the failure it is — that distinction is the whole of
  `rateLimited`. Reporting is deduplicated per pause window because up to
  `MAX_CONCURRENT_REQUESTS` threads hit the same reset within milliseconds of
  each other; before it existed an exhausted budget parked a run for eight
  minutes in silence. `GitHubClientRateLimitTest` covers each shape.
- **The secondary limits are paced against, not retried into.** GitHub allows
  900 points a minute on the REST API — a read costs one, anything that writes
  costs five — answers 403 or 429 with `Retry-After` past that, and reports the
  budget in no header. `RequestPacer` books a request's points in `sendBounded`
  before it takes a stream permit, scheduling it so that no 60-second window
  holds more than 900: an account too big for the limit becomes a slower run
  rather than a refused one. A run that fits in the window waits nowhere — the
  519 points a 101-repository check costs are all due immediately — so
  measuring the pacer against a small account shows it doing nothing, which is
  the design and not evidence that it can go. The response cache is no help
  here either: a 304 is still a request and still costs its point. Three things
  follow. The wait is taken before the permit, so a paced thread is not sitting
  on one of the hundred streams. `sendRequest` hands every pause to `backOffFor`
  as well as sleeping it out, because `Thread.sleep` stops one thread and the
  limit belongs to the token — the other ninety-nine would spend the pause
  earning refusals of their own, and the three attempts would go to requests
  that never had a chance. And a point the gate held back is booked where it
  lands, not where it was asked for, or the window empties itself while the run
  is parked and the whole budget goes out the moment it lifts.
- **A listing's pages after the first go out together.** `remainingPages` reads
  the page count off the `Link` header's `rel="last"` and asks for 2..N at
  once, falling back to the `rel="next"` walk when GitHub sends no `rel="last"`
  — the final page of a listing, and any cursor-paginated endpoint. The walk
  paid a round trip per page in series before anything else could start.
  Matching a `page` parameter takes the leading `?` or `&` — every listing URL
  also carries `per_page`, which contains the same six characters.
- **The semaphore is the floor, so request count is the only lever left.**
  Tracing a check of the 101-repository `ArloL` account on 2026-09-19: 742
  requests before the GraphQL query replaced 268 of them, p50 latency 249ms,
  and 77% of a 2.57s fetch window saturated. Wall clock is
  `requests × latency ÷ permits`, and every other term is at its limit.
  Latency is GitHub's, and roughly two thirds of it is the token: `GET /zen`
  resolves no permissions and reads no repository, and answers in
  95ms unauthenticated against 286ms authenticated over a 40ms round trip. That
  ~190ms is charged to every request whatever it asks for, and a fine-grained
  PAT and an OAuth token pay it alike — which is why dropping a request is
  worth so much more than making one cheaper. The permit count is at GitHub's
  documented 100-concurrent ceiling: 120 simultaneous requests all answered
  200, 150 drew 11×403 and 270 drew 135×403. Depth is held at two levels by
  `RepositoryCheckerRequestShapeTest`. Conditional requests are not a lever
  either: a 304 costs what the 200 it replaces costs, so the response cache
  buys rate-limit budget and no time.
  `docs/performance/where-a-checks-time-goes.md` carries the measurements, the
  dead ends and what is left to take.
- **A GET drifty has seen before is asked conditionally, and GitHub does not
  charge the 304.** `GitHubClient.get` sends `If-None-Match` from the
  `ResponseCache` the state file implements, and `CachedHttpResponse` hands the
  kept body back as a 200, so the forty typed methods above it never learn
  anything was cached. Measured: `x-ratelimit-used` held across two 304s and
  moved on the next 200. Three things this does not do. It does not reduce
  request count, so it buys nothing against the secondary limits. It does not
  honour `cache-control: max-age=60` — drifty revalidates every read, or a
  `--fix` could read the state it had just written. And it cannot serve a
  response the current token may not read: the conditional request still
  carries the token, so a downgraded one is answered 403, never 304.
- **A 304 drops the `Link` header, so the cache keeps it.** `remainingPages`
  reads a listing's page count off `rel="last"`. Restore the cached header on
  the synthesised response, or a cached first page ends the listing there and
  drifty checks the first 100 repositories of an account and reports the rest
  as MISSING — no error, no failed request, a wrong answer.
  `GitHubClientCacheTest.aCachedListingStillReachesItsSecondPage` fails by
  returning one repository instead of two.
- **`GitHubCheck.main` saves state on every check, not only under `--fix`.**
  An earlier version gated `stateStore.save(...)` on `fix`, which was right
  while the file held only secret baselines — a plain check wrote none. It
  now also holds the response cache, so gating the save left every check
  cold: nothing a read filled in ever reached disk. Do not reintroduce the
  guard; the test that would have caught its return was deliberately removed
  for being tautological, so nothing else will. This is also why
  `StateStore.save` writes through a temp file and moves it into place
  atomically instead of truncating the target: a run that saves on every
  check, not only the occasional `--fix`, is a run where a Ctrl-C or a full
  disk mid-write is no longer rare enough to ignore. That temp file is created
  `rw-------` as a *creation attribute*, never chmod'd after the write: the
  file holds cached response bodies — organization settings, member logins,
  webhook payload URLs — and a mode set afterwards leaves a world-readable
  window, which on a shared CI runner is the whole of the exposure. Windows
  throws `UnsupportedOperationException` for the attribute and falls back to
  the directory's ACL, so the two tests covering it are `@EnabledOnOs({
  OS.LINUX, OS.MAC })`.
