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
 * The account listing used to be pure head: nothing about a repository could be
 * read until the page naming it had answered. Traced on a 101-repository
 * account on 2026-09-19 that was 596ms of a 3.09s fetch with fewer than ten
 * requests in flight — a quarter of the run spent on one response.
 * <p>
 * The config already names every repository, so the listing is no longer what
 * says which ones to check. It runs beside them, and answers the two questions
 * only it can: which repositories GitHub lists that the config does not declare
 * ({@code UNKNOWN}), and which it declares that GitHub does not list
 * ({@code MISSING}).
 */
class RepositoryListingOverlapTest {

	/**
	 * How long the listing is held. Long enough that a checker which waits for
	 * it cannot possibly reach a repository inside the window, and short enough
	 * not to dominate the suite. Nothing else here is delayed.
	 */
	private static final int LISTING_DELAY_MILLIS = 1000;

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().containerThreads(50))
			.build();

	@Test
	void everyDeclaredRepositoryIsReachedWhileTheListingIsStillOnTheWire()
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

		assertThat(waitBeforeTheFirstRepository())
				.as("a repository is reached without waiting for the listing")
				.isLessThan(LISTING_DELAY_MILLIS / 2);
	}

	private static void check(GitHubClient client) throws Exception {
		new RepositoryChecker(client, false).check(
				"acme",
				client.listUserReposAsync("acme"),
				List.of(Desired.repository("one"), Desired.repository("two"))
		);
	}

	/**
	 * How long after asking for the listing the first repository was reached.
	 * The listing is held open, so a checker that waits for it cannot get there
	 * inside the delay, and one that does not get there within a few
	 * milliseconds of asking.
	 */
	private static long waitBeforeTheFirstRepository() {
		return arrival("/repos/acme/") - arrival("/user/repos");
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
	 * Two pages of one repository each, both held open. Page one carries the
	 * {@code rel="last"} the client reads the page count off.
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
										.withFixedDelay(LISTING_DELAY_MILLIS)
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
										.withFixedDelay(LISTING_DELAY_MILLIS)
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
