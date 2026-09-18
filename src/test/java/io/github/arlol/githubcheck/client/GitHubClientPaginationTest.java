package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * How a listing longer than one page is read.
 * <p>
 * GitHub answers a paginated request with {@code rel="last"} beside
 * {@code rel="next"}, so the page count is known from the first answer and the
 * rest can be asked for together. Walking {@code rel="next"} instead paid one
 * round trip per page in series — the two pages of {@code /user/repos} for a
 * 101-repository account were the first 1.31s of a check, before any repository
 * had started.
 * <p>
 * {@code GitHubClientTest} already covers what a multi-page listing returns,
 * with and without a {@code rel="last"}; what is here is the schedule, which
 * needs stubs that take measurable time.
 */
class GitHubClientPaginationTest {

	private static final int PAGES = 4;
	private static final int DELAY_MILLIS = 200;

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort().containerThreads(20))
			.build();

	@Test
	void everyPageAfterTheFirstIsFetchedAtOnce() {
		stubPages(PAGES);

		List<RepositorySummaryResponse> repos = client().listOrgRepos("owner")
				.orElseThrow();

		assertThat(repos).extracting(RepositorySummaryResponse::name)
				.as(
						"pages are collected in order, not in the order they answer"
				)
				.containsExactly("repo-1", "repo-2", "repo-3", "repo-4");
		assertThat(maxInFlight()).as("pages 2..%d overlap", PAGES)
				.isEqualTo(PAGES - 1);
	}

	/**
	 * A listing whose first page says {@code rel="last"} is page 1 — GitHub's
	 * answer when everything fits — asks for nothing else.
	 */
	@Test
	void aSinglePageListingAsksForNoMorePages() {
		wm.stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.willReturn(
								page(1).withHeader(
										"Link",
										link(1, "first") + ", "
												+ link(1, "last")
								)
						)
		);

		assertThat(client().listOrgRepos("owner").orElseThrow()).hasSize(1);
		assertThat(wm.getAllServeEvents()).hasSize(1);
	}

	/**
	 * A page that fails has to fail the listing, whichever way the pages were
	 * fetched — a short listing silently missing a page is what makes
	 * {@code --fix} delete what it could not see.
	 */
	@Test
	void aPageThatFailsFailsTheListing() {
		stubPages(PAGES);
		wm.stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.withQueryParam("page", equalTo("3"))
						.willReturn(okJson("{}").withStatus(500))
		);

		assertThatThrownBy(() -> client().listOrgRepos("owner"))
				.isInstanceOf(GitHubApiException.class)
				.hasMessageContaining("HTTP 500 fetching next page");
	}

	private GitHubClient client() {
		return new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"test-token"
		);
	}

	/**
	 * The first page answers immediately and names the last; the rest answer
	 * after {@link #DELAY_MILLIS} so overlapping arrivals are measurable.
	 */
	private static void stubPages(int pages) {
		wm.stubFor(
				get(urlPathEqualTo("/orgs/owner/repos"))
						.withQueryParam("page", absent())
						.willReturn(
								page(1).withHeader(
										"Link",
										link(2, "next") + ", "
												+ link(pages, "last")
								)
						)
		);
		IntStream.rangeClosed(2, pages)
				.forEach(
						number -> wm.stubFor(
								get(urlPathEqualTo("/orgs/owner/repos"))
										.withQueryParam(
												"page",
												equalTo(String.valueOf(number))
										)
										.willReturn(
												page(number).withFixedDelay(
														DELAY_MILLIS
												)
										)
						)
				);
	}

	private static ResponseDefinitionBuilder page(int number) {
		return okJson("""
				[{"name": "repo-%d", "archived": false, "visibility": "public"}]
				""".formatted(number));
	}

	private static String link(int page, String rel) {
		return "<" + wm.getRuntimeInfo().getHttpBaseUrl()
				+ "/orgs/owner/repos?per_page=100&type=all&page=" + page
				+ ">; rel=\"" + rel + "\"";
	}

	/**
	 * WireMock logs a request when it arrives and holds it for
	 * {@link #DELAY_MILLIS}, so requests logged inside one delay of each other
	 * were in flight together. The first page is left out: it carries no delay,
	 * and it is the one request that could not have overlapped anything. The
	 * ampersand in the filter is what tells a {@code page} parameter from the
	 * {@code per_page} every listing URL also carries.
	 */
	private static int maxInFlight() {
		List<Long> received = wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.filter(request -> request.getUrl().contains("&page="))
				.map(request -> request.getLoggedDate().getTime())
				.toList();
		return received.stream()
				.mapToInt(
						start -> (int) received.stream()
								.filter(
										other -> other >= start
												&& other < start + DELAY_MILLIS
								)
								.count()
				)
				.max()
				.orElseThrow();
	}

}
