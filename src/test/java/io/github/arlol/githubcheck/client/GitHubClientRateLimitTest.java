package io.github.arlol.githubcheck.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * What the client does when GitHub says to slow down.
 * <p>
 * Two different things arrive the same way. A response that is otherwise fine
 * but reports the budget spent holds the data, so it is returned — after a
 * pause, which is what keeps the <em>next</em> request from being the one that
 * is refused. A response GitHub refused <em>because</em> of a limit holds
 * nothing, so it is re-sent: handing a secondary limit's 403 to the caller
 * reported a group as unreadable that had never been read. A plain 403 — a
 * token without a scope — has to keep reaching the caller as the failure it is,
 * and carries neither {@code Retry-After} nor an exhausted budget to tell it
 * apart by.
 * <p>
 * The pauses here are the shortest the client can compute: {@code Retry-After:
 * 0} and a reset that is already due both come out as one second, since it adds
 * a second of slack to whatever GitHub names.
 */
class GitHubClientRateLimitTest {

	private static final String PATH = "/repos/owner/repo/private-vulnerability-reporting";

	@RegisterExtension
	static WireMockExtension wm = WireMockExtension.newInstance().build();

	@Test
	void aSecondaryRateLimitIsWaitedOutAndTheRequestResent() {
		refusedOnce(aResponse().withStatus(403).withHeader("Retry-After", "0"));

		assertThat(client().getPrivateVulnerabilityReporting("owner", "repo"))
				.isTrue();
		assertThat(wm.getAllServeEvents()).hasSize(2);
	}

	@Test
	void a429IsWaitedOutAndTheRequestResent() {
		refusedOnce(aResponse().withStatus(429).withHeader("Retry-After", "0"));

		assertThat(client().getPrivateVulnerabilityReporting("owner", "repo"))
				.isTrue();
		assertThat(wm.getAllServeEvents()).hasSize(2);
	}

	/**
	 * The primary limit says nothing but {@code X-RateLimit-Remaining: 0} and
	 * the epoch second it resets at.
	 */
	@Test
	void anExhaustedBudgetIsWaitedOutAndTheRequestResent() {
		refusedOnce(
				aResponse().withStatus(403)
						.withHeader("X-RateLimit-Remaining", "0")
						.withHeader("X-RateLimit-Reset", dueNow())
		);

		assertThat(client().getPrivateVulnerabilityReporting("owner", "repo"))
				.isTrue();
		assertThat(wm.getAllServeEvents()).hasSize(2);
	}

	/**
	 * The response holds what was asked for, so it is returned rather than
	 * re-sent — but the budget is gone, so the thread waits before the next
	 * request goes out on the same connection.
	 */
	@Test
	void aGoodResponseThatSpendsTheLastOfTheBudgetIsReturnedAfterAPause() {
		wm.stubFor(
				get(urlPathEqualTo(PATH)).willReturn(
						answered().withHeader("X-RateLimit-Remaining", "0")
								.withHeader(
										"X-RateLimit-Reset",
										dueNextSecond()
								)
				)
		);

		Instant before = Instant.now();
		assertThat(client().getPrivateVulnerabilityReporting("owner", "repo"))
				.isTrue();

		assertThat(wm.getAllServeEvents()).hasSize(1);
		assertThat(Duration.between(before, Instant.now()))
				.as("the pause happened")
				.isGreaterThanOrEqualTo(Duration.ofSeconds(1));
	}

	/**
	 * A token that cannot read the endpoint gets 403 forever. Retrying it would
	 * triple every failing request in a run and delay the report by two pauses
	 * per group.
	 */
	@Test
	void aPlain403IsNotARateLimitAndIsNotResent() {
		wm.stubFor(
				get(urlPathEqualTo(PATH))
						.willReturn(aResponse().withStatus(403))
		);

		assertThatThrownBy(
				() -> client().getPrivateVulnerabilityReporting("owner", "repo")
		).isInstanceOf(GitHubApiException.class).hasMessageContaining("403");
		assertThat(wm.getAllServeEvents()).hasSize(1);
	}

	/**
	 * A token genuinely out of budget must not park the run forever: after the
	 * attempts are spent the refusal reaches the caller as the failure it has
	 * turned out to be.
	 */
	@Test
	void aRateLimitThatNeverLiftsGivesUpAfterThreeAttempts() {
		wm.stubFor(
				get(urlPathEqualTo(PATH)).willReturn(
						aResponse().withStatus(429)
								.withHeader("Retry-After", "0")
				)
		);

		assertThatThrownBy(
				() -> client().getPrivateVulnerabilityReporting("owner", "repo")
		).isInstanceOf(GitHubApiException.class).hasMessageContaining("429");
		assertThat(wm.getAllServeEvents()).hasSize(3);
	}

	/**
	 * GitHub also sends {@code Retry-After} on the 202 of a computation it has
	 * not finished, which is not a limit and must not pause anything.
	 */
	@Test
	void aRetryAfterOnAResponseThatWasNotRefusedIsIgnored() {
		wm.stubFor(
				get(urlPathEqualTo(PATH))
						.willReturn(answered().withHeader("Retry-After", "60"))
		);

		Instant before = Instant.now();
		assertThat(client().getPrivateVulnerabilityReporting("owner", "repo"))
				.isTrue();

		assertThat(Duration.between(before, Instant.now()))
				.isLessThan(Duration.ofSeconds(5));
	}

	/**
	 * What the endpoint answers when nothing is refusing it. Returned fresh so
	 * a caller can hang rate-limit headers off it; nothing here depends on the
	 * body, only on the request succeeding.
	 */
	private static ResponseDefinitionBuilder answered() {
		return aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"enabled\": true}");
	}

	/** Refused once, then answered. */
	private static void refusedOnce(ResponseDefinitionBuilder refusal) {
		wm.stubFor(
				get(urlPathEqualTo(PATH)).inScenario("rate limit")
						.whenScenarioStateIs(STARTED)
						.willReturn(refusal)
						.willSetStateTo("lifted")
		);
		wm.stubFor(
				get(urlPathEqualTo(PATH)).inScenario("rate limit")
						.whenScenarioStateIs("lifted")
						.willReturn(answered())
		);
	}

	/**
	 * The epoch second this one is in, which is already partly gone — so the
	 * pause is whatever is left of it, plus the client's second of slack.
	 */
	private static String dueNow() {
		return String.valueOf(Instant.now().getEpochSecond());
	}

	/**
	 * The next whole epoch second, which is between one and two seconds away.
	 * {@link #dueNow} would be too, but the header is whole seconds and the
	 * current one is already partly spent, so its pause has no lower bound to
	 * assert on.
	 */
	private static String dueNextSecond() {
		return String.valueOf(Instant.now().getEpochSecond() + 1);
	}

	private static GitHubClient client() {
		return new GitHubClient(
				wm.getRuntimeInfo().getHttpBaseUrl(),
				"test-token"
		);
	}

}
