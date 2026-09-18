package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * The account listing is pure head: nothing about a repository can be read
 * until the page that names it has answered, and page two cannot be asked for
 * until page one's {@code Link} header has arrived. For a 101-repository
 * account that was the first 1.24s of a 4.45s check, with the request semaphore
 * idle throughout — one page's latency of it spent waiting for a page whose
 * repositories the checker was not going to touch first anyway.
 * <p>
 * So the first page's repositories are checked while the rest of the listing is
 * still arriving. The pages after the first already go out together; this is
 * the one round trip left in front of them.
 */
class RepositoryListingOverlapTest {

	/**
	 * How long page two is held. Long enough that a checker which waits for the
	 * whole listing cannot possibly reach a repository inside it, and short
	 * enough not to dominate the suite. Nothing else here is delayed, so a
	 * repository request that arrives within this window arrived while page two
	 * was still open.
	 */
	private static final int PAGE_TWO_DELAY_MILLIS = 1000;

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().containerThreads(50))
			.build();

	@Test
	void theFirstPagesRepositoriesAreCheckedWhilePageTwoIsStillArriving()
			throws Exception {
		GitHubClient client = new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"t"
		);
		stubListing();
		stubRepositories();

		// The first check of a fresh JVM spends several hundred milliseconds
		// loading classes before it sends a repository's first request. That
		// would be spent inside page two's delay and hide most of the head
		// start being measured, so it is spent here instead.
		check(client);
		wm.resetRequests();
		check(client);

		assertThat(headStartOfTheFirstPage()).as(
				"page one's repository is checked while page two is still open"
		).isGreaterThan(PAGE_TWO_DELAY_MILLIS / 2);
	}

	private static void check(GitHubClient client) throws Exception {
		new RepositoryChecker(client, false).check(
				"acme",
				client.listUserReposPaged("acme"),
				List.of(Desired.repository("one"), Desired.repository("two"))
		);
	}

	/**
	 * How long before the repository only page two names the repository page
	 * one names was reached.
	 * <p>
	 * Page two is held open, so its repository cannot be reached until it
	 * answers; page one's can be reached at once. A checker that waits for the
	 * whole listing submits both in the same instant and this is a handful of
	 * milliseconds — no assumption about when WireMock logs a delayed response,
	 * only that a repository cannot be read before the page naming it has
	 * arrived.
	 */
	private static long headStartOfTheFirstPage() {
		return arrival("/repos/acme/two") - arrival("/repos/acme/one");
	}

	private static long arrival(String path) {
		return arrival(request -> request.getUrl().startsWith(path));
	}

	private static long arrival(Predicate<LoggedRequest> matching) {
		return wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.filter(matching)
				.mapToLong(request -> request.getLoggedDate().getTime())
				.min()
				.orElseThrow();
	}

	/**
	 * Two pages of one repository each. Page one carries the {@code rel="last"}
	 * the client reads the page count off; page two is held open.
	 */
	private static void stubListing() {
		wm.stubFor(
				get(urlPathEqualTo("/user/repos"))
						.withQueryParam("page", equalTo("2"))
						.atPriority(1)
						.willReturn(
								aResponse().withStatus(200)
										.withHeader(
												"Content-Type",
												"application/json"
										)
										.withBody(page("two"))
										.withFixedDelay(PAGE_TWO_DELAY_MILLIS)
						)
		);
		wm.stubFor(
				get(urlPathEqualTo("/user/repos")).atPriority(5)
						.willReturn(
								aResponse().withStatus(200)
										.withHeader(
												"Content-Type",
												"application/json"
										)
										.withHeader(
												"Link",
												"<" + wm.getRuntimeInfo()
														.getHttpBaseUrl()
														+ "/user/repos?per_page=100&type=owner&page=2>; rel=\"last\""
										)
										.withBody(page("one"))
						)
		);
	}

	private static String page(String name) {
		return """
				[{"name": "%s", "archived": false, "visibility": "public"}]
				""".formatted(name);
	}

	/**
	 * Answers anything under a repository, because what is asserted is when the
	 * checker first reached a repository, not what it made of the answer. Each
	 * entry comes back errored on this stub and that is fine — an entry cannot
	 * be errored before its request was sent.
	 */
	private static void stubRepositories() {
		wm.stubFor(
				get(urlPathMatching("/repos/acme/.*")).willReturn(okJson("{}"))
		);
	}

}
