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
		public void store(String key, String etag, String body, String link) {
			entries.put(key, new Entry(etag, body, link));
		}

		@Override
		public void confirm(String key) {
			// Nothing to age here; DriftyState stamps a date.
		}

	}

	private InMemoryCache cache;
	private GitHubClient client;
	private String baseUrl;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		cache = new InMemoryCache();
		baseUrl = wm.getHttpBaseUrl();
		client = new GitHubClient(baseUrl, "test-token", cache);
	}

	@Test
	void aSecondReadIsConditionalAndAnsweredFromTheCachedBody() {
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", absent())
						.willReturn(
								okJson(DETAILS).withHeader("ETag", "\"v1\"")
						)
		);
		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", equalTo("\"v1\""))
						.willReturn(
								aResponse().withStatus(304)
										.withHeader("ETag", "\"v1\"")
						)
		);

		assertThat(client.getRepo("owner", "repo").name()).isEqualTo("repo");
		assertThat(client.getRepo("owner", "repo").name()).isEqualTo("repo");

		verify(
				1,
				getRequestedFor(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", equalTo("\"v1\""))
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
						.willReturn(
								okJson(DETAILS).withHeader("ETag", "\"v1\"")
						)
		);
		client.getRepo("owner", "repo");

		stubFor(
				get(urlPathEqualTo("/repos/owner/repo"))
						.withHeader("If-None-Match", equalTo("\"v1\""))
						.willReturn(
								aResponse().withStatus(403)
										.withBody("{\"message\":\"Forbidden\"}")
						)
		);

		assertThatThrownBy(() -> client.getRepo("owner", "repo"))
				.isInstanceOf(GitHubApiException.class)
				.hasMessageContaining("403");
	}

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
								).withHeader("ETag", "\"p1\"")
										.withHeader(
												"Link",
												"<" + pageTwoUrl
														+ ">; rel=\"last\""
										)
						)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.withQueryParam("page", absent())
						.withHeader("If-None-Match", equalTo("\"p1\""))
						.willReturn(
								aResponse().withStatus(304)
										.withHeader("ETag", "\"p1\"")
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

}
