package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * That the client really does pace itself, rather than owning a
 * {@link RequestPacer} nobody consults. The pacer's own arithmetic is
 * {@code RequestPacerTest}; what these cover is the wiring — the window is
 * asked before the request goes out, a write is charged what a write costs, and
 * a refusal one thread collects holds back the threads that have not sent yet.
 */
class GitHubClientPacingTest {

	private static final String PATH = "/repos/owner/repo/vulnerability-alerts";
	private static final String OTHER_PATH = "/repos/owner/other/vulnerability-alerts";
	private static final Duration WINDOW = Duration.ofMillis(500);

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance().build();

	@Test
	void theRequestPastTheWindowWaitsForItToTurnOver() {
		wm.stubFor(
				get(urlPathEqualTo(PATH))
						.willReturn(aResponse().withStatus(204))
		);
		GitHubClient client = client(new RequestPacer(2, WINDOW));

		Instant before = Instant.now();
		for (int i = 0; i < 3; i++) {
			assertThat(client.getVulnerabilityAlerts("owner", "repo")).isTrue();
		}

		assertThat(wm.getAllServeEvents()).hasSize(3);
		assertThat(Duration.between(before, Instant.now()))
				.as("the third read waited for the first to leave the window")
				.isGreaterThanOrEqualTo(WINDOW);
	}

	/**
	 * A write costs five points where a read costs one, so one PUT is the whole
	 * of a five-point window and the read after it waits.
	 */
	@Test
	void aWriteSpendsFivePointsOfTheWindow() {
		wm.stubFor(
				put(urlPathEqualTo(PATH))
						.willReturn(aResponse().withStatus(204))
		);
		wm.stubFor(
				get(urlPathEqualTo(PATH))
						.willReturn(aResponse().withStatus(204))
		);
		GitHubClient client = client(new RequestPacer(5, WINDOW));

		Instant before = Instant.now();
		client.enableVulnerabilityAlerts("owner", "repo");
		assertThat(client.getVulnerabilityAlerts("owner", "repo")).isTrue();

		assertThat(Duration.between(before, Instant.now()))
				.as("the read waited out the window the write filled")
				.isGreaterThanOrEqualTo(WINDOW);
	}

	/**
	 * The limit belongs to the token, not to the request that happened to be
	 * refused: a run has up to ninety requests in flight, and the eighty-nine
	 * that were not refused have to stop too. Measured on when GitHub received
	 * the second request rather than on what the caller waited, because that is
	 * the thing the limit counts.
	 */
	@Test
	void aRefusedRequestHoldsBackAThreadThatHasNotSentYet() throws Exception {
		refusedOnce();
		wm.stubFor(
				get(urlPathEqualTo(OTHER_PATH))
						.willReturn(aResponse().withStatus(204))
		);
		GitHubClient client = client(new RequestPacer());

		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			Future<Boolean> refused = executor.submit(
					() -> client.getVulnerabilityAlerts("owner", "repo")
			);
			long refusedAt = awaitFirstRequest();
			// Far enough after the refusal that the gate is certainly up, and
			// far short of the second it lasts.
			Thread.sleep(100);

			assertThat(client.getVulnerabilityAlerts("owner", "other"))
					.isTrue();

			assertThat(receivedAt(OTHER_PATH) - refusedAt)
					.as("the second thread's request waited for the gate")
					.isGreaterThanOrEqualTo(700);
			assertThat(refused.get()).isTrue();
		}
	}

	/**
	 * Refused once with the shortest pause the client can compute: a second.
	 */
	private static void refusedOnce() {
		wm.stubFor(
				get(urlPathEqualTo(PATH)).inScenario("rate limit")
						.whenScenarioStateIs(STARTED)
						.willReturn(
								aResponse().withStatus(403)
										.withHeader("Retry-After", "0")
						)
						.willSetStateTo("lifted")
		);
		wm.stubFor(
				get(urlPathEqualTo(PATH)).inScenario("rate limit")
						.whenScenarioStateIs("lifted")
						.willReturn(aResponse().withStatus(204))
		);
	}

	/**
	 * When the server saw the request it is about to refuse. Taken as the
	 * earliest of what it has logged rather than off one end of the list, which
	 * WireMock is free to order either way.
	 */
	private static long awaitFirstRequest() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			List<ServeEvent> events = wm.getAllServeEvents();
			if (!events.isEmpty()) {
				return events.stream()
						.mapToLong(
								event -> event.getRequest()
										.getLoggedDate()
										.getTime()
						)
						.min()
						.orElseThrow();
			}
			Thread.sleep(10);
		}
		throw new AssertionError("no request reached the server");
	}

	private static long receivedAt(String path) {
		return wm.getAllServeEvents()
				.stream()
				.map(ServeEvent::getRequest)
				.filter(request -> path.equals(request.getUrl()))
				.mapToLong(request -> request.getLoggedDate().getTime())
				.min()
				.orElseThrow();
	}

	private static GitHubClient client(RequestPacer pacer) {
		return new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"test-token",
				4,
				ResponseCache.NONE,
				pacer
		);
	}

}
