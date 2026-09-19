package io.github.arlol.githubcheck.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * How the client spaces requests so that GitHub's points-per-minute secondary
 * limit is never the thing that stops a run.
 * <p>
 * The clock and the sleep are both supplied here, so what these assert is the
 * schedule the pacer computes rather than how long a thread really slept.
 */
class RequestPacerTest {

	private static final Duration WINDOW = Duration.ofSeconds(60);
	private static final int POINTS = 10;

	private final AtomicLong now = new AtomicLong(1_000_000);
	private final List<Long> slept = new ArrayList<>();

	private final RequestPacer pacer = pacer(POINTS);

	@Test
	void aRunThatFitsInTheWindowIsNotSlowedAtAll() throws Exception {
		for (int i = 0; i < POINTS; i++) {
			pacer.awaitTurn(1);
		}

		assertThat(slept).isEmpty();
	}

	@Test
	void thePointPastTheLimitWaitsForTheFirstToLeaveTheWindow()
			throws Exception {
		for (int i = 0; i < POINTS; i++) {
			pacer.awaitTurn(1);
		}
		now.addAndGet(5_000);

		assertThat(pacer.awaitTurn(1))
				.as("what it waited is what it reports having waited")
				.isEqualTo(WINDOW.toMillis() - 5_000);
		assertThat(slept).containsExactly(WINDOW.toMillis() - 5_000);
	}

	/**
	 * A read costs one point and anything that writes costs five, so two writes
	 * spend as much of the budget as ten reads — which is why the pacer counts
	 * points rather than requests.
	 */
	@Test
	void twoWritesSpendAsMuchOfTheBudgetAsTenReads() throws Exception {
		pacer.awaitTurn(RequestPacer.pointsFor("PATCH"));
		pacer.awaitTurn(RequestPacer.pointsFor("DELETE"));

		assertThat(slept).isEmpty();

		pacer.awaitTurn(RequestPacer.pointsFor("GET"));

		assertThat(slept).containsExactly(WINDOW.toMillis());
	}

	@Test
	void everyMethodThatWritesCostsFivePoints() {
		assertThat(RequestPacer.pointsFor("GET")).isEqualTo(1);
		assertThat(RequestPacer.pointsFor("HEAD")).isEqualTo(1);
		assertThat(RequestPacer.pointsFor("POST")).isEqualTo(5);
		assertThat(RequestPacer.pointsFor("PUT")).isEqualTo(5);
		assertThat(RequestPacer.pointsFor("PATCH")).isEqualTo(5);
		assertThat(RequestPacer.pointsFor("DELETE")).isEqualTo(5);
	}

	/**
	 * The whole point of the gate: one thread's {@code Retry-After} holds back
	 * the requests that have not gone out yet, not only the one that was
	 * refused. Without it the other eighty-nine threads keep sending into a
	 * limit GitHub has just said is tripped.
	 */
	@Test
	void aBackOffHoldsBackARequestWhoseBudgetIsUntouched() throws Exception {
		pacer.backOffFor(Duration.ofSeconds(30));

		pacer.awaitTurn(1);

		assertThat(slept).containsExactly(30_000L);
	}

	@Test
	void aBackOffRaisedWhileAThreadIsWaitingIsHonoured() throws Exception {
		RequestPacer[] self = new RequestPacer[1];
		boolean[] raised = { false };
		self[0] = new RequestPacer(POINTS, WINDOW, now::get, millis -> {
			tick(millis);
			if (!raised[0]) {
				raised[0] = true;
				self[0].backOffFor(Duration.ofSeconds(10));
			}
		});
		self[0].backOffFor(Duration.ofSeconds(30));

		self[0].awaitTurn(1);

		assertThat(slept).containsExactly(30_000L, 10_000L);
	}

	/**
	 * A point held back by the gate is booked where it lands, not where it was
	 * asked for. Booking it at the instant it was asked for would let the
	 * window empty itself while the run was parked, and the budget the gate was
	 * raised to protect would go out in one burst the moment it lifted.
	 */
	@Test
	void aPointTheGateHeldBackIsBookedWhereItLands() throws Exception {
		RequestPacer paused = pacer(2);
		paused.awaitTurn(1);
		paused.backOffFor(Duration.ofSeconds(30));
		paused.awaitTurn(1);
		now.addAndGet(30_000);
		slept.clear();

		// The window holds one point from before the gate and one from the
		// instant it lifted. The first has just left it; the second has thirty
		// seconds to go.
		paused.awaitTurn(1);
		paused.awaitTurn(1);

		assertThat(slept).containsExactly(30_000L);
	}

	/**
	 * A backed-off run picks the window up where it left off rather than
	 * starting a fresh one, so a limit tripped twice does not let twice the
	 * budget through.
	 */
	@Test
	void theWindowKeepsCountingAcrossABackOff() throws Exception {
		for (int i = 0; i < POINTS; i++) {
			pacer.awaitTurn(1);
		}
		pacer.backOffFor(Duration.ofSeconds(10));

		pacer.awaitTurn(1);

		assertThat(slept).containsExactly(WINDOW.toMillis());
	}

	private RequestPacer pacer(int pointsPerWindow) {
		return new RequestPacer(pointsPerWindow, WINDOW, now::get, this::tick);
	}

	/** Sleeps by moving the test clock, so nothing here waits in real time. */
	private void tick(long millis) {
		slept.add(millis);
		now.addAndGet(millis);
	}

}
