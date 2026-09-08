package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * GitHub answers over one HTTP/2 connection whose
 * SETTINGS_MAX_CONCURRENT_STREAMS is 100, and the JDK client does not queue
 * past it — it throws {@code IOException: too many concurrent streams}. The
 * checker starts one virtual thread per repository, so without a bound an
 * account with more than ~100 repositories loses the race and the run dies
 * before checking anything.
 */
class GitHubClientConcurrencyTest {

	private static final int LIMIT = 4;
	private static final int REQUESTS = 16;
	private static final int DELAY_MILLIS = 200;

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance()
			.options(
					// Well above REQUESTS, so the only thing that can bound
					// concurrency here is the client.
					wireMockConfig().dynamicPort().containerThreads(60)
			)
			.build();

	@Test
	void neverHasMoreRequestsInFlightThanTheLimit() throws Exception {
		wm.stubFor(
				get(
						urlPathMatching(
								"/repos/owner/repo-.*/vulnerability-alerts"
						)
				).willReturn(
						aResponse().withStatus(204).withFixedDelay(DELAY_MILLIS)
				)
		);
		GitHubClient client = new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"test-token",
				LIMIT
		);

		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<Boolean>> futures = IntStream.range(0, REQUESTS)
					.mapToObj(
							i -> executor.submit(
									() -> client.getVulnerabilityAlerts(
											"owner",
											"repo-" + i
									)
							)
					)
					.toList();
			for (Future<Boolean> future : futures) {
				assertThat(future.get()).isTrue();
			}
		}

		int observed = maxInFlight();
		assertThat(observed).isLessThanOrEqualTo(LIMIT);
		// Guards the guard: a client that sent them one at a time would pass
		// the assertion above without proving anything.
		assertThat(observed).isGreaterThan(1);
	}

	@Test
	void interruptedWaitingForAPermitFailsInsteadOfHanging() {
		GitHubClient client = new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"test-token",
				LIMIT
		);

		// An interrupted thread never gets a permit — Semaphore.acquire throws
		// rather than waiting — so this is the wait, without the other threads
		// it would take to cause one.
		Thread.currentThread().interrupt();
		try {
			assertThatThrownBy(
					() -> client.getVulnerabilityAlerts("owner", "repo-0")
			).isInstanceOf(GitHubApiException.class)
					.hasMessageContaining("interrupted");
			assertThat(Thread.currentThread().isInterrupted())
					.as("the interrupt is passed on, not swallowed")
					.isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	/**
	 * WireMock logs when it received a request and holds it for
	 * {@link #DELAY_MILLIS} before answering, so a request is in flight over
	 * {@code [received, received + delay)} and overlapping windows are requests
	 * that were in flight together.
	 */
	private static int maxInFlight() {
		List<Long> received = wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.map(request -> request.getLoggedDate().getTime())
				.toList();
		assertThat(received).hasSize(REQUESTS);
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
