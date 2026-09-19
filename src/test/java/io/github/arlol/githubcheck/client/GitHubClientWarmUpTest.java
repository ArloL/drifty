package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * The run's first ninety requests go out together and every one of them holds a
 * permit while the connection they share is still being opened. Traced on a
 * 101-repository account, that first wave answered in 413ms against the 235ms
 * the rest of the run saw — DNS, TCP and TLS, paid once but waited for ninety
 * times over.
 */
@WireMockTest
class GitHubClientWarmUpTest {

	@Test
	void warmUpOpensTheConnectionBeforeThereIsAnythingToSend(
			WireMockRuntimeInfo wm
	) {
		stubFor(head(urlPathEqualTo("/")).willReturn(ok()));
		GitHubClient client = new GitHubClient(wm.getHttpBaseUrl(), "t");

		client.warmUp();

		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(
						() -> verify(1, headRequestedFor(urlPathEqualTo("/")))
				);
	}

	/**
	 * Nothing waits for it and nothing depends on it: the connection a run
	 * needs is opened by its first real request either way, and that request is
	 * where a host that cannot be reached has always been reported.
	 */
	@Test
	void warmUpNeverFailsTheRun() {
		GitHubClient client = new GitHubClient("http://127.0.0.1:1", "t");

		assertThatCode(client::warmUp).doesNotThrowAnyException();
	}

}
