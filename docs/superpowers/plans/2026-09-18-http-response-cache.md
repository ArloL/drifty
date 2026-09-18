# HTTP Response Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a check of an unchanged account cost no primary rate limit, by
sending `If-None-Match` on every GET and answering a 304 from a body cached in
the state file.

**Architecture:** Every read in `GitHubClient` funnels through one private
method, `get(String url)`, and its callers read only `statusCode()`, `body()`
and two header lookups. The cache lives entirely inside that method. The client
talks to a `ResponseCache` port it defines itself, so `client` keeps knowing
nothing about persistence; `DriftyState` implements that port and the existing
state file carries the entries.

**Tech Stack:** Java 25, `java.net.http`, Jackson (snake_case), JUnit 5,
WireMock, AssertJ, Maven wrapper (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-09-18-http-response-cache-design.md`

## Global Constraints

- **Always revalidate.** GitHub sends `cache-control: private, max-age=60`;
  drifty ignores it. A cached body is only ever used after a 304 in the same
  run. Never serve a cached body without asking GitHub.
- **Only a 200 carrying an `ETag` becomes an entry.** 204, 304-without-entry,
  403, 404 and an ETag-less 200 cache nothing.
- **A 304 drops the `Link` header.** The cached `link` must be restored onto
  the synthesised response or pagination silently stops after page one.
- **Prune at 180 days**, measured on each entry's `last_validated` date.
- **No version bump.** `cache` is added to the state file the way
  `organizations` was — an older drifty ignores the key.
- **No new command-line argument.** `GitHubCheck.BOOLEAN_FLAGS` and
  `VALUE_OPTIONS` do not change.
- **Formatting:** run `./mvnw spotless:apply` (or let `./mvnw verify` fail you)
  — the build enforces the project's formatter, and tabs are the indent.
- **Package boundary:** `io.github.arlol.githubcheck.client` must not import
  anything from `io.github.arlol.githubcheck.state`. The dependency runs the
  other way.

---

### Task 1: The `ResponseCache` port and the conditional GET

**Files:**
- Create: `src/main/java/io/github/arlol/githubcheck/client/ResponseCache.java`
- Create: `src/main/java/io/github/arlol/githubcheck/client/CachedHttpResponse.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/client/GitHubClient.java`
  (constructors around line 101–126; `get(String)` at line 2611)
- Test: `src/test/java/io/github/arlol/githubcheck/client/GitHubClientCacheTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public interface ResponseCache` with `Entry lookup(String key)`,
    `void store(String key, String etag, String body, String link)`,
    `void confirm(String key)`, nested
    `record Entry(String etag, String body, String link)`, and a no-op constant
    `ResponseCache.NONE`.
  - `public GitHubClient(String baseUrl, String token, ResponseCache cache)`
    and `public GitHubClient(String token, ResponseCache cache)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/io/github/arlol/githubcheck/client/GitHubClientCacheTest.java`:

```java
package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * A GET drifty has seen before is asked conditionally, and GitHub's 304 is
 * answered from the body it kept. A 304 costs no primary rate limit, which is
 * the whole point: a 742-request check of an unchanged account spends none of
 * the 5000 an hour.
 * <p>
 * Every case here is about what may and may not become an entry. Serving a
 * cached body for anything but a 304 in the same run would make drifty report
 * settings GitHub has since changed.
 */
@WireMockTest
class GitHubClientCacheTest {

	private static final String DETAILS = """
			{
				"id": 1,
				"name": "repo",
				"private": false,
				"fork": false,
				"archived": false,
				"disabled": false,
				"is_template": false,
				"visibility": "public",
				"default_branch": "main",
				"has_issues": true,
				"has_projects": true,
				"has_wiki": true,
				"has_discussions": false,
				"has_pages": false,
				"allow_forking": true,
				"web_commit_signoff_required": false,
				"allow_squash_merge": true,
				"allow_merge_commit": true,
				"allow_rebase_merge": true,
				"allow_auto_merge": false,
				"delete_branch_on_merge": false,
				"allow_update_branch": false
			}
			""";

	/** What DriftyState will do for real, in ten lines. */
	private static final class InMemoryCache implements ResponseCache {

		private final Map<String, Entry> entries = new ConcurrentHashMap<>();

		@Override
		public Entry lookup(String key) {
			return entries.get(key);
		}

		@Override
		public void store(
				String key,
				String etag,
				String body,
				String link
		) {
			entries.put(key, new Entry(etag, body, link));
		}

		@Override
		public void confirm(String key) {
			// Nothing to age here; DriftyState stamps a date.
		}

	}

	private InMemoryCache cache;
	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		cache = new InMemoryCache();
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token", cache);
	}

	@Test
	void aSecondReadIsConditionalAndAnsweredFromTheCachedBody() {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", absent())
						.willReturn(okJson(DETAILS).withHeader("ETag", "\\"v1\\""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", equalTo("\\"v1\\""))
						.willReturn(
								aResponse().withStatus(304)
										.withHeader("ETag", "\\"v1\\"")
						)
		);

		assertThat(client.getRepo("owner", "repo").name()).isEqualTo("repo");
		assertThat(client.getRepo("owner", "repo").name()).isEqualTo("repo");

		verify(
				1,
				getRequestedFor(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", equalTo("\\"v1\\""))
		);
	}

	@Test
	void aResponseWithoutAnEtagIsNotCached() {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.willReturn(okJson(DETAILS))
		);

		client.getRepo("owner", "repo");

		assertThat(cache.lookup("/repos/owner/repo")).isNull();
	}

	/**
	 * A token that loses access is answered 403, not 304 — the conditional
	 * request still reaches GitHub carrying it. Serving the body drifty read
	 * under the old token would report settings this one cannot see.
	 */
	@Test
	void aForbiddenResponseIsNotAnsweredFromTheCache() {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", absent())
						.willReturn(okJson(DETAILS).withHeader("ETag", "\\"v1\\""))
		);
		client.getRepo("owner", "repo");

		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", equalTo("\\"v1\\""))
						.willReturn(
								aResponse().withStatus(403)
										.withBody("{\\"message\\":\\"Forbidden\\"}")
						)
		);

		assertThatThrownBy(() -> client.getRepo("owner", "repo"))
				.isInstanceOf(GitHubApiException.class)
				.hasMessageContaining("403");
	}

}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./mvnw -DskipNativeTests -Dtest=GitHubClientCacheTest test`

Expected: a compilation failure — `ResponseCache` does not exist and
`GitHubClient` has no three-argument constructor taking one. That is the
correct first failure; the interface is what Step 3 writes.

- [ ] **Step 3: Write `ResponseCache`**

Create `src/main/java/io/github/arlol/githubcheck/client/ResponseCache.java`:

```java
package io.github.arlol.githubcheck.client;

/**
 * Where {@link GitHubClient} keeps the body behind an ETag.
 * <p>
 * A 304 carries no body, so answering one means having kept the last. The port
 * is declared here rather than taken as a state type because the client has no
 * business knowing where the answers are written down; {@code DriftyState}
 * implements it and the state file holds the entries.
 * <p>
 * Nothing here is a cache in the HTTP sense: drifty revalidates every read and
 * ignores {@code max-age}, so an entry is only ever used to fill in a 304 that
 * GitHub has just sent. A drift detector that answered from a cache without
 * asking could report settings that had since changed.
 */
public interface ResponseCache {

	/**
	 * @param link the {@code Link} header the cached response carried, or
	 *             null. GitHub's 304 does not repeat it, and
	 *             {@code remainingPages} reads the page count off it — without
	 *             this a cached first page ends the listing.
	 */
	record Entry(String etag, String body, String link) {
	}

	/** The entry for {@code key}, or null when there is none. */
	Entry lookup(String key);

	/** Records the body of a 200 that carried {@code etag}. */
	void store(String key, String etag, String body, String link);

	/**
	 * Notes that {@code key} was confirmed current by a 304. What keeps an
	 * entry alive: pruning drops whatever no run has confirmed lately.
	 */
	void confirm(String key);

	/** Caches nothing, for a client with no state file behind it. */
	ResponseCache NONE = new ResponseCache() {

		@Override
		public Entry lookup(String key) {
			return null;
		}

		@Override
		public void store(String key, String etag, String body, String link) {
			// Deliberately nothing.
		}

		@Override
		public void confirm(String key) {
			// Deliberately nothing.
		}

	};

}
```

- [ ] **Step 4: Write `CachedHttpResponse`**

Create `src/main/java/io/github/arlol/githubcheck/client/CachedHttpResponse.java`:

```java
package io.github.arlol.githubcheck.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

/**
 * GitHub's 304, wearing the 200 it stands for.
 * <p>
 * Every caller above {@code GitHubClient.get} reads {@code statusCode()},
 * {@code body()} and the odd header, so filling those three in from the cached
 * entry is the whole of what a hit has to look like — the forty typed methods
 * above it never learn that anything was cached. Everything else is delegated
 * to the real 304, whose {@code uri()}, {@code request()} and {@code version()}
 * are the ones a caller would want anyway.
 */
final class CachedHttpResponse implements HttpResponse<String> {

	private final HttpResponse<String> notModified;
	private final ResponseCache.Entry entry;
	private final HttpHeaders headers;

	CachedHttpResponse(
			HttpResponse<String> notModified,
			ResponseCache.Entry entry
	) {
		this.notModified = notModified;
		this.entry = entry;
		this.headers = withCachedLink(notModified.headers(), entry.link());
	}

	/**
	 * A 304 does not repeat {@code Link}, so it is put back. Keeping the rest
	 * of the 304's headers matters: {@code X-RateLimit-Remaining} on it is
	 * current, and the one cached with the body is not.
	 */
	private static HttpHeaders withCachedLink(HttpHeaders live, String link) {
		if (link == null) {
			return live;
		}
		Map<String, List<String>> merged = new LinkedHashMap<>(live.map());
		merged.put("link", List.of(link));
		return HttpHeaders.of(merged, (name, value) -> true);
	}

	@Override
	public int statusCode() {
		return 200;
	}

	@Override
	public String body() {
		return entry.body();
	}

	@Override
	public HttpHeaders headers() {
		return headers;
	}

	@Override
	public HttpRequest request() {
		return notModified.request();
	}

	@Override
	public Optional<HttpResponse<String>> previousResponse() {
		return notModified.previousResponse();
	}

	@Override
	public Optional<SSLSession> sslSession() {
		return notModified.sslSession();
	}

	@Override
	public URI uri() {
		return notModified.uri();
	}

	@Override
	public HttpClient.Version version() {
		return notModified.version();
	}

}
```

- [ ] **Step 5: Add the cache to `GitHubClient`**

In `GitHubClient`, add the field beside `inFlight`:

```java
	private final ResponseCache cache;
```

Add the two public constructors and thread `cache` through the existing chain.
The existing constructors keep working by passing `ResponseCache.NONE`:

```java
	public GitHubClient(String token) {
		this("https://api.github.com", token);
	}

	public GitHubClient(String token, ResponseCache cache) {
		this("https://api.github.com", token, cache);
	}

	public GitHubClient(String baseUrl, String token) {
		this(baseUrl, token, ResponseCache.NONE);
	}

	public GitHubClient(String baseUrl, String token, ResponseCache cache) {
		this(baseUrl, token, MAX_CONCURRENT_REQUESTS, cache);
	}

	GitHubClient(String baseUrl, String token, int maxConcurrentRequests) {
		this(baseUrl, token, maxConcurrentRequests, ResponseCache.NONE);
	}

	GitHubClient(
			String baseUrl,
			String token,
			int maxConcurrentRequests,
			ResponseCache cache
	) {
		// existing body, plus:
		this.cache = cache;
	}
```

Replace `get(String url)` (currently at line 2611):

```java
	/**
	 * One GET, asked conditionally when drifty has seen the answer before.
	 * <p>
	 * GitHub does not charge a 304 against the primary rate limit — an
	 * unchanged account costs nothing of the 5000 an hour — and the request
	 * still carries the current token, so a 304 is proof that this token may
	 * read the resource. {@code max-age} is ignored on purpose: a body is only
	 * ever used to fill in a 304 GitHub has just sent.
	 */
	private HttpResponse<String> get(String url) {
		String key = cacheKey(url);
		ResponseCache.Entry cached = cache.lookup(key);
		HttpRequest.Builder builder = requestBuilder(url).GET();
		if (cached != null) {
			builder.header(HEADER_IF_NONE_MATCH, cached.etag());
		}
		HttpResponse<String> resp = sendRequest(builder.build());
		if (resp.statusCode() == 304 && cached != null) {
			cache.confirm(key);
			return new CachedHttpResponse(resp, cached);
		}
		if (resp.statusCode() == 200) {
			resp.headers()
					.firstValue("ETag")
					.ifPresent(
							etag -> cache.store(
									key,
									etag,
									resp.body(),
									resp.headers()
											.firstValue("Link")
											.orElse(null)
							)
					);
		}
		return resp;
	}

	/**
	 * What an entry is filed under: the URL without the host, so the file reads
	 * as {@code /repos/ArloL/drifty} and a WireMock port never reaches it.
	 */
	private String cacheKey(String url) {
		return url.startsWith(baseUrl) ? url.substring(baseUrl.length()) : url;
	}
```

Add the header constant beside the other `HEADER_` constants:

```java
	private static final String HEADER_IF_NONE_MATCH = "If-None-Match";
```

- [ ] **Step 6: Run the test and watch it pass**

Run: `./mvnw -DskipNativeTests -Dtest=GitHubClientCacheTest test`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 7: Run the whole suite**

Run: `./mvnw -DskipNativeTests test`
Expected: `Tests run: 745, Failures: 0, Errors: 0, Skipped: 1` — 742 before,
plus this file's three. Every existing client test constructs `GitHubClient`
without a cache and so gets `ResponseCache.NONE`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/client/ResponseCache.java \
        src/main/java/io/github/arlol/githubcheck/client/CachedHttpResponse.java \
        src/main/java/io/github/arlol/githubcheck/client/GitHubClient.java \
        src/test/java/io/github/arlol/githubcheck/client/GitHubClientCacheTest.java
git commit -m "Ask conditionally for a GET drifty has seen before

GitHub does not charge a 304 against the primary rate limit, so an account
nobody has touched costs none of the 5000 an hour. The conditional request
still carries the current token, which is what makes a 304 proof that this
token may read the resource.

Claude-Session: https://claude.ai/code/session_01LxZG7vxUHzoEMtYmAjifKi"
```

---

### Task 2: Keep the `Link` header, or a listing ends at page one

**Files:**
- Modify: `src/test/java/io/github/arlol/githubcheck/client/GitHubClientCacheTest.java`
- Verify only (no change expected): `CachedHttpResponse.withCachedLink`

**Interfaces:**
- Consumes: `ResponseCache`, `CachedHttpResponse`, the three-argument
  `GitHubClient` constructor from Task 1.
- Produces: nothing new.

This task exists because the failure it prevents is silent. GitHub's 304 does
not repeat `Link`; `remainingPages` reads the page count off `rel="last"`, and
with the header gone it falls through to the `rel="next"` walk, finds nothing
there either, and returns no further pages. Drifty would check the first 100
repositories of a larger account and report every other declared repository as
MISSING — no error, no failed request, a wrong answer.

- [ ] **Step 1: Write the failing test**

Append to `GitHubClientCacheTest`:

```java
	/**
	 * GitHub's 304 drops {@code Link}. Verified against the live API on
	 * 2026-09-18: the 200 carried {@code rel="next"} and {@code rel="last"},
	 * the 304 carried the ETag and nothing else.
	 * <p>
	 * This fails by returning one repository instead of two, not by erroring,
	 * which is exactly how it would reach a user: an account's second hundred
	 * repositories reported MISSING.
	 */
	@Test
	void aCachedListingStillReachesItsSecondPage() {
		String pageTwoUrl = baseUrl
				+ "/orgs/owner/repos?per_page=100&type=all&page=2";
		stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.withQueryParam("page", absent())
						.withHeader("If-None-Match", absent())
						.willReturn(
								okJson(
										"""
												[{"name": "one", "archived": false, "visibility": "public"}]
												"""
								).withHeader("ETag", "\\"p1\\"")
										.withHeader(
												"Link",
												"<" + pageTwoUrl
														+ ">; rel=\\"last\\""
										)
						)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.withQueryParam("page", absent())
						.withHeader("If-None-Match", equalTo("\\"p1\\""))
						.willReturn(
								aResponse().withStatus(304)
										.withHeader("ETag", "\\"p1\\"")
						)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.withQueryParam("page", equalTo("2"))
						.willReturn(
								okJson(
										"""
												[{"name": "two", "archived": false, "visibility": "public"}]
												"""
								)
						)
		);

		assertThat(client.listOrgRepos("owner").orElseThrow())
				.extracting(RepositorySummaryResponse::name)
				.containsExactly("one", "two");

		assertThat(client.listOrgRepos("owner").orElseThrow())
				.as("the cached first page still names page two")
				.extracting(RepositorySummaryResponse::name)
				.containsExactly("one", "two");
	}
```

Keep the base URL, which the `rel="last"` link has to be built from. Add the
field beside `client` and set it in `setUp`:

```java
	private String baseUrl;
```

```java
		baseUrl = wm.getHttpBaseUrl();
		client = new GitHubClient(baseUrl, "test-token", cache);
```

No new imports: `RepositorySummaryResponse` is in the same package.

- [ ] **Step 2: Run the test**

Run: `./mvnw -DskipNativeTests -Dtest=GitHubClientCacheTest test`

Expected: PASS, because Task 1's `CachedHttpResponse.withCachedLink` already
restores the header. If it FAILS with
`Expecting actual: ["one"] to contain exactly: ["one", "two"]`, the header is
not being restored — fix `withCachedLink`, do not weaken the test.

- [ ] **Step 3: Prove the test can fail**

Temporarily change `withCachedLink` to `return live;` unconditionally, run the
test, and confirm it reports one repository instead of two. Restore the method.
A test that cannot fail is not a guard, and this is the one failure mode in the
whole feature that produces a wrong answer rather than an error.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/github/arlol/githubcheck/client/GitHubClientCacheTest.java
git commit -m "Pin that a cached listing still names its later pages

GitHub's 304 drops Link and remainingPages reads the page count off it, so a
cached first page without the header ends the listing there — the second
hundred repositories of an account reported MISSING, with no failed request to
show for it.

Claude-Session: https://claude.ai/code/session_01LxZG7vxUHzoEMtYmAjifKi"
```

---

### Task 3: `DriftyState` holds the entries

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/state/DriftyState.java`
- Test: `src/test/java/io/github/arlol/githubcheck/state/StateStoreTest.java`

**Interfaces:**
- Consumes: `ResponseCache` and `ResponseCache.Entry` from Task 1.
- Produces: `DriftyState implements ResponseCache`, plus
  `public record CacheEntry(String etag, String body, String link, String lastValidated)`
  nested in `DriftyState`, serialised under the `cache` key.

- [ ] **Step 1: Write the failing test**

Append to `StateStoreTest`:

```java
	/**
	 * Round-tripped here for the reason the secret records are: the native
	 * image's reflection metadata comes from what the suite traces, so a field
	 * no test serialises is missing from the shipped binary.
	 */
	@Test
	void save_thenLoad_roundTripsCacheEntries(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store(
				"/repos/owner/repo",
				"\\"v1\\"",
				"{\\"name\\":\\"repo\\"}",
				"<https://api.github.com/x?page=2>; rel=\\"last\\""
		);
		store.save(path, state);

		var loaded = store.load(path);

		var entry = loaded.lookup("/repos/owner/repo");
		assertThat(entry.etag()).isEqualTo("\\"v1\\"");
		assertThat(entry.body()).isEqualTo("{\\"name\\":\\"repo\\"}");
		assertThat(entry.link())
				.isEqualTo("<https://api.github.com/x?page=2>; rel=\\"last\\"");
	}

	/**
	 * A cache is worth writing down on its own. Before this, a run that
	 * recorded no secret wrote no file — and a cache that is never saved is
	 * never a cache.
	 */
	@Test
	void save_writesTheFile_whenOnlyTheCacheHasAnything(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store("/repos/owner/repo", "\\"v1\\"", "{}", null);

		store.save(path, state);

		assertThat(path).exists();
	}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./mvnw -DskipNativeTests -Dtest=StateStoreTest test`
Expected: compilation failure — `DriftyState` has no `store` or `lookup`.

- [ ] **Step 3: Implement it**

In `DriftyState`, add the imports `java.time.LocalDate` and
`io.github.arlol.githubcheck.client.ResponseCache`, make the class implement
the port, and add:

```java
	/**
	 * One cached response. {@code lastValidated} is an ISO date rather than a
	 * timestamp because the only question asked of it is how many days old it
	 * is.
	 */
	public record CacheEntry(
			String etag,
			String body,
			String link,
			String lastValidated
	) {
	}
```

the field beside `organizations`:

```java
	/**
	 * Bodies behind ETags, keyed by the URL without its host. Added without a
	 * version bump, like {@code organizations}: an older drifty ignores the
	 * key and a newer one treats a file without it as a cold cache.
	 */
	ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
```

and the three port methods:

```java
	@Override
	public ResponseCache.Entry lookup(String key) {
		CacheEntry entry = cache.get(key);
		return entry == null ? null
				: new ResponseCache.Entry(
						entry.etag(),
						entry.body(),
						entry.link()
				);
	}

	@Override
	public void store(String key, String etag, String body, String link) {
		cache.put(key, new CacheEntry(etag, body, link, today()));
	}

	@Override
	public void confirm(String key) {
		cache.computeIfPresent(
				key,
				(unused, entry) -> new CacheEntry(
						entry.etag(),
						entry.body(),
						entry.link(),
						today()
				)
		);
	}

	private static String today() {
		return LocalDate.now().toString();
	}
```

Extend `isEmpty()` so a cache alone is worth saving:

```java
	@JsonIgnore
	public boolean isEmpty() {
		return cache.isEmpty()
				&& repositories.values().stream().allMatch(DriftyState::isEmpty)
				&& organizations.values()
						.stream()
						.allMatch(
								orgState -> orgState.actionSecrets.isEmpty()
										&& orgState.webhookSecrets.isEmpty()
						);
	}
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -DskipNativeTests -Dtest=StateStoreTest test`
Expected: PASS, including the existing
`save_writesNoFile_whenStateRecordsNothing` — that state has an empty cache.

- [ ] **Step 5: Run the whole suite**

Run: `./mvnw -DskipNativeTests test`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/state/DriftyState.java \
        src/test/java/io/github/arlol/githubcheck/state/StateStoreTest.java
git commit -m "Keep cached bodies in the state file

DriftyState implements the client's ResponseCache port, so the client still
knows nothing about where answers are written down. The cache key is added
without a version bump, the way organizations was.

A state holding only cache entries is no longer empty: a cache that is never
saved is never a cache.

Claude-Session: https://claude.ai/code/session_01LxZG7vxUHzoEMtYmAjifKi"
```

---

### Task 4: Prune at 180 days

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/state/DriftyState.java`
- Modify: `src/main/java/io/github/arlol/githubcheck/state/StateStore.java:46-56`
- Test: `src/test/java/io/github/arlol/githubcheck/state/StateStoreTest.java`

**Interfaces:**
- Consumes: `DriftyState.CacheEntry`, `store`, `lookup` from Task 3.
- Produces: `public void DriftyState.pruneCache(LocalDate cutoff)`, called by
  `StateStore.save` with `LocalDate.now().minusDays(180)`.

- [ ] **Step 1: Write the failing test**

Append to `StateStoreTest`:

```java
	/**
	 * An entry costs about 1.5 KB and evicting one costs a charged request, so
	 * the window is set by what is worth remembering rather than by file size.
	 * A repository checked twice a year keeps its entry.
	 */
	@Test
	void save_dropsCacheEntriesNoRunHasConfirmedForHalfAYear(
			@TempDir Path dir
	) throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store("/repos/owner/fresh", "\\"f\\"", "{}", null);
		state.store("/repos/owner/stale", "\\"s\\"", "{}", null);
		state.cache.put(
				"/repos/owner/stale",
				new DriftyState.CacheEntry(
						"\\"s\\"",
						"{}",
						null,
						LocalDate.now().minusDays(181).toString()
				)
		);

		store.save(path, state);
		var loaded = store.load(path);

		assertThat(loaded.lookup("/repos/owner/fresh")).isNotNull();
		assertThat(loaded.lookup("/repos/owner/stale")).isNull();
	}

	/** A date drifty cannot read is an entry it cannot trust to age out. */
	@Test
	void save_dropsACacheEntryWhoseDateIsUnreadable(@TempDir Path dir)
			throws Exception {
		var path = dir.resolve("drifty-state.json");
		var state = new DriftyState();
		state.store("/repos/owner/keep", "\\"k\\"", "{}", null);
		state.cache.put(
				"/repos/owner/broken",
				new DriftyState.CacheEntry("\\"b\\"", "{}", null, "not-a-date")
		);

		store.save(path, state);

		assertThat(store.load(path).lookup("/repos/owner/broken")).isNull();
	}
```

Add `import java.time.LocalDate;` to the test.

- [ ] **Step 2: Run the test and watch it fail**

Run: `./mvnw -DskipNativeTests -Dtest=StateStoreTest test`
Expected: both new tests FAIL — the stale and the broken entry are still there,
because nothing prunes yet.

- [ ] **Step 3: Implement pruning**

In `DriftyState`:

```java
	/**
	 * Forgets every entry no run has confirmed since {@code cutoff}, and every
	 * entry whose date cannot be read — a stamp drifty cannot parse is one it
	 * cannot age out, so it goes now rather than never.
	 */
	public void pruneCache(LocalDate cutoff) {
		cache.values().removeIf(entry -> validatedBefore(entry, cutoff));
	}

	private static boolean validatedBefore(CacheEntry entry, LocalDate cutoff) {
		try {
			return LocalDate.parse(entry.lastValidated()).isBefore(cutoff);
		} catch (RuntimeException e) {
			return true;
		}
	}
```

In `StateStore.save`, prune before the emptiness check — a state whose entries
have all aged out has nothing left to write:

```java
	public void save(Path path, DriftyState state) throws IOException {
		state.pruneCache(LocalDate.now().minusDays(CACHE_DAYS));
		if (state.isEmpty()) {
			return;
		}
		// existing body
	}
```

with the constant and import at the top of `StateStore`:

```java
	/**
	 * How long an unconfirmed cache entry is kept. An entry costs about 1.5 KB
	 * and evicting one costs a charged request, so this is set by what is worth
	 * remembering, not by file size.
	 */
	private static final int CACHE_DAYS = 180;
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -DskipNativeTests -Dtest=StateStoreTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/state/DriftyState.java \
        src/main/java/io/github/arlol/githubcheck/state/StateStore.java \
        src/test/java/io/github/arlol/githubcheck/state/StateStoreTest.java
git commit -m "Forget a cached response no run has confirmed for 180 days

Without it the file keeps every repository the account has ever had, and every
URL belonging to a group the config later stopped managing. A date drifty
cannot parse is dropped too: a stamp it cannot read is one it cannot age out.

Claude-Session: https://claude.ai/code/session_01LxZG7vxUHzoEMtYmAjifKi"
```

---

### Task 5: Hand the state to the client

**Files:**
- Modify: `src/main/java/io/github/arlol/githubcheck/GitHubCheck.java:126` and
  the `--state` line of `usage()` at :404
- Modify: `CLAUDE.md`
- Test: `src/test/java/io/github/arlol/githubcheck/GitHubCheckTest.java`

**Interfaces:**
- Consumes: `GitHubClient(String token, ResponseCache cache)` from Task 1;
  `DriftyState implements ResponseCache` from Task 3.
- Produces: nothing later tasks rely on.

- [ ] **Step 1: Write the failing test**

`usage_namesEveryArgumentDriftyAccepts` already guards the argument lists. What
is not guarded is that the help text stops describing the file as secrets only.
Append to `GitHubCheckTest`:

```java
	/**
	 * The file is no longer only secret baselines, and it is about to go from
	 * nothing to roughly a megabyte on a run that records no secret. A user
	 * reading --help should find out from there.
	 */
	@Test
	void usage_saysTheStateFileHoldsTheResponseCache() {
		assertThat(GitHubCheck.usage()).contains("cache");
	}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `./mvnw -DskipNativeTests -Dtest=GitHubCheckTest test`
Expected: FAIL — the help text says only "Secret baselines to read and write."

- [ ] **Step 3: Wire the cache in and update the help text**

At `GitHubCheck.java:126`, hand the loaded state to the client:

```java
		// The state is the client's response cache as well as the secret
		// baselines: a GET drifty has seen before is asked conditionally, and
		// GitHub charges no rate limit for the 304 that comes back.
		var client = new GitHubClient(token, state);
```

In `usage()`, replace the `--state` lines:

```java
				  --state <path>   Secret baselines and cached responses to read and
				                   write. Default: drifty-state.json beside the config.
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -DskipNativeTests -Dtest=GitHubCheckTest test`
Expected: PASS, `usage_namesEveryArgumentDriftyAccepts` included.

- [ ] **Step 5: Record the two non-obvious things in CLAUDE.md**

Add to the "Adding or changing a managed setting" list, after the bullet
beginning "**The semaphore is the floor now**":

```markdown
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
```

- [ ] **Step 6: Run the whole suite**

Run: `./mvnw -DskipNativeTests test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/arlol/githubcheck/GitHubCheck.java \
        src/test/java/io/github/arlol/githubcheck/GitHubCheckTest.java \
        CLAUDE.md
git commit -m "Hand the state file to the client as its response cache

A check now spends primary rate limit only on what changed. The file it is
written to is no longer only secret baselines, and a run that records no secret
now writes about a megabyte where it used to write nothing, so --help says so.

Claude-Session: https://claude.ai/code/session_01LxZG7vxUHzoEMtYmAjifKi"
```

---

### Task 6: Reachability metadata and the shipped binary

**Files:**
- Modify: `src/main/resources/META-INF/native-image/reachability-metadata.json`
- Modify: `src/test/resources/META-INF/native-image/reachability-metadata.json`

**Interfaces:**
- Consumes: everything above.
- Produces: a native image that can deserialise `DriftyState.CacheEntry`.

`DriftyState` and its nested classes are plain classes, not `client`/`pkl`
records, so `ReachabilityMetadata` does not add them from ClassGraph — their
metadata comes from what the suite traces. Task 3's round-trip is what makes
`CacheEntry` traceable; this task is what moves it into the shipped image.
Without it the binary loads a state file and fails on the new key.

- [ ] **Step 1: Retrace with the agent**

Run: `./mvnw test -Dagent=true`
Expected: BUILD SUCCESS, with a fresh `target/native/agent-output`.

- [ ] **Step 2: Repartition the two scoped files**

```bash
./mvnw test-compile
./mvnw exec:java@reachability-metadata
```

Do not pass `-Dexec.arguments`; it would leak into the phase-bound
`pkl-codegen-java` execution. The splitter reads the default agent-output path.

- [ ] **Step 3: Confirm `CacheEntry` reached the production scope**

```bash
python3 -c "
import json
m = json.load(open('src/main/resources/META-INF/native-image/reachability-metadata.json'))
names = [e['type'] for e in m['reflection'] if isinstance(e.get('type'), str)]
print([n for n in names if 'CacheEntry' in n or 'DriftyState' in n])
"
```

Expected: a list containing `...DriftyState$CacheEntry`. An empty list means
the trace did not reach it — check that Task 3's round-trip test ran in Step 1.

- [ ] **Step 4: Build and smoke-run the production image**

```bash
./mvnw -DskipTests package
./target/drifty-macos-0.0.1-SNAPSHOT --self-test
```

Expected: `self-test OK`.

- [ ] **Step 5: Exercise the cache in the shipped binary**

`--self-test` does not touch a state file, so do this once by hand against a
real account:

```bash
cd ~/Developer/drifty-arlol
mise exec -- /Users/aokeeffe/Developer/drifty/target/drifty-macos-0.0.1-SNAPSHOT \
  --config drifty.pkl --state /tmp/cache-check.json
mise exec -- /Users/aokeeffe/Developer/drifty/target/drifty-macos-0.0.1-SNAPSHOT \
  --config drifty.pkl --state /tmp/cache-check.json
```

Expected: the first run writes `/tmp/cache-check.json` (about 1.1 MB), both
runs report the same counts (101 checked, 96 OK, 5 unknown, no drift), and the
second run's `x-ratelimit-used` barely moves. Check the last with:

```bash
curl --silent -H "Authorization: Bearer $DRIFTY_GITHUB_TOKEN" \
  https://api.github.com/rate_limit | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["resources"]["core"])'
```

before and after the second run. Expected delta: a few tens of requests, not
742. If it is 742, the entries are not being read back — check the cache key
against what the file holds.

- [ ] **Step 6: Full verify**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS, including the native test image and
`NativeExecutableIT`.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/META-INF/native-image/reachability-metadata.json \
        src/test/resources/META-INF/native-image/reachability-metadata.json
git commit -m "Register the cache entry for the shipped image

DriftyState's nested classes are plain classes, so their metadata comes from
what the suite traces rather than from the ClassGraph augmentation. Without
this the binary reads a state file and fails on the key it just learned to
write.

Claude-Session: https://claude.ai/code/session_01LxZG7vxUHzoEMtYmAjifKi"
```

---

## Out of scope

- **`--export` is not cached.** `ExportRunner` is handed a bare
  `new GitHubClient(token)` at `GitHubCheck.java:88` and loads no state file,
  so it keeps paying full rate limit. It is the heaviest read path and worth
  doing, but `--export` has no `--state` semantics today and giving it some is
  its own decision.
- **Request count does not change.** All 742 requests still go out, so nothing
  here helps the secondary limits — the concurrency and points-per-minute
  throttles that answer 403 with `Retry-After`. Those need fewer or slower
  requests.
- **`OrganizationChecker` and `RepositoryChecker` are untouched.** They already
  hold a `DriftyState` for secret baselines; the cache reaches them through the
  client they were constructed with.
