# Caching responses so an unchanged account costs no rate limit

A check of a 101-repository account sends 742 requests. GitHub allows 5000 an
hour, so drifty runs out after six checks and the seventh parks until the
window resets.

A conditional request fixes that. GitHub answers `If-None-Match` with `304 Not
Modified` when the resource has not changed, and **a 304 does not count against
the primary rate limit**. Measured against the live API on 2026-09-18:

```
plain 200                 x-ratelimit-used: 1443
conditional (304)         x-ratelimit-used: 1443
conditional again (304)   x-ratelimit-used: 1443
plain 200 again           x-ratelimit-used: 1444
```

An account nobody has touched since the last run therefore costs nothing, and
the hourly ceiling stops applying to how often drifty may run.

A 304 carries no body, so drifty has to keep the last one. The bodies of one
run total 1.07 MB across 698 responses, and they go in the state file beside
the secret baselines.

## Where the cache sits

Every read in `GitHubClient` goes through one method:

```java
private HttpResponse<String> get(String url) {
	return sendRequest(requestBuilder(url).GET().build());
}
```

Callers of the ~40 typed methods above it read only `statusCode()`, `body()`
and two header lookups. So the feature fits entirely inside that method: send
`If-None-Match` when an entry exists, and answer a 304 with the cached body as
though GitHub had sent 200. The typed methods, both checkers, `Fanout` and the
semaphore do not change.

Writes keep going out unconditionally. A PATCH changes the resource, GitHub
issues a new ETag, and the next run's conditional request gets a 200 — the
cache corrects itself without being invalidated.

## What an entry holds

Only a 200 that carried an ETag becomes an entry. A 204, a 404, a 403 and an
ETag-less 200 pass straight through and cache nothing.

```json
"cache": {
  "/repos/ArloL/drifty": {
    "etag": "\"dae5c6c7…\"",
    "last_validated": "2026-09-18",
    "body": "{\"id\":123,\"name\":\"drifty\",…}"
  }
}
```

The key is the URL with `baseUrl` stripped, so an entry reads as
`/repos/ArloL/drifty` rather than carrying a host, and a WireMock port does not
end up in the file.

`link` is stored only for a response that carried one, and it is the trap this
design exists to avoid. **GitHub's 304 drops the `Link`
header.** Verified on the same date:

```
200:  link: <…&page=2>; rel="next", <…&page=2>; rel="last"
      etag: "43f042c8…"
304:  etag: "43f042c8…"
      (no link)
```

`remainingPages` reads the page count off `rel="last"`. Without the cached
header it would see none, fall through to the `rel="next"` walk, find nothing
there either, and return no further pages — leaving drifty silently checking
the first 100 repositories of a larger account and reporting the rest as
missing. So `link` is cached with the body and restored on a hit.

## The 304 path

`get` returns `HttpResponse<String>`, and synthesising one is the only awkward
part. A small `CachedResponse` implements the interface by answering
`statusCode()` with 200, `body()` with the cached body and `headers()` with the
cached `Link`, and delegates everything else to the real 304 response — whose
`uri()`, `request()` and `version()` are the ones a caller would want anyway.

Three properties fall out of always revalidating:

- **Never stale.** GitHub sends `cache-control: private, max-age=60`, and
  drifty ignores it deliberately. Honouring it would let a `--fix` run read the
  state it had just written for up to a minute.
- **Token-safe.** A conditional request still reaches GitHub carrying the
  current token. A 304 is proof that *this* token may read the resource; a
  downgraded token is answered 403 or 404 and never served from the cache.
- **Self-correcting.** Anything that changes the resource changes the ETag.

## Pruning

Each entry records the date it was last validated, and a 304 refreshes that
date as surely as a 200 does — the entry was confirmed current either way.
Entries no run has confirmed for 30 days are dropped when the state is saved. Without that the file keeps every
repository the account has ever had, and every URL belonging to a group the
config later stopped managing.

## What changes outside the client

`DriftyState` gains a `cache` map, held in a `ConcurrentHashMap` like the rest
of it: ninety threads read and write it at once. It is added without a version
bump, the way `organizations` was — an older drifty ignores the key, and a
newer one treats a file without it as a cold cache.

`isEmpty()` has to count cache entries, which changes one visible thing: today
a run that records no secret writes no file at all, and from now on every run
writes about 1.1 MB. The help text for `--state` says "Secret baselines to read
and write" and needs to say that it holds the response cache too.

No new command-line argument. The cache is on, and deleting the file is how a
user turns it off for one run.

## Testing

- A second call for the same URL carries `If-None-Match` and returns the first
  call's parsed value, with the stub asserting it was asked conditionally.
- A 304 on a paginated listing still reaches page 2. This is the `Link` trap;
  the test fails with the header uncached, and it fails by checking too few
  repositories rather than by erroring.
- A 403 after a cached 200 reaches the caller as a failure and is not served
  from the cache.
- An entry whose `last_validated` is 31 days old is gone after a save; one from
  yesterday survives.
- `StateStoreTest` round-trips an entry, for the reason it already round-trips
  a repository and an organization secret: the native image's reflection
  metadata comes from what the suite traces.

## Files

- `client/GitHubClient.java` — `get`, `CachedResponse`, the cache field
- `state/DriftyState.java` — the `cache` map, its accessors, `isEmpty`
- `state/StateStore.java` — pruning on save
- `GitHubCheck.java` — hand the state to the client, help text

## Out of scope

Request count does not change. All 742 requests are still sent, so this does
nothing for the secondary limits — the concurrency and points-per-minute
throttles that answer 403 with `Retry-After` and park a run. Those need fewer
or slower requests, and are a separate piece of work.
