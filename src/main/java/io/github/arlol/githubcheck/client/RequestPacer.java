package io.github.arlol.githubcheck.client;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Keeps a run inside GitHub's points-per-minute secondary limit, and holds
 * every thread back when one of them is told to wait.
 * <p>
 * The secondary limits are not the 5000-an-hour budget and are not reported in
 * any header: GitHub documents no more than 900 points per minute for the REST
 * API, where a read costs one point and anything that writes costs five, and
 * answers 403 or 429 with {@code Retry-After} once that is exceeded. The
 * primary budget says nothing about it — a run served entirely out of the
 * response cache is charged almost nothing and still spends a point per request
 * here.
 * <p>
 * Two mechanisms, and the first is what matters. Every request reserves its
 * points before it is sent, and a reservation is scheduled far enough ahead
 * that no 60-second window ever holds more than {@link #POINTS_PER_MINUTE} of
 * them, so an account big enough to exceed the limit is slowed rather than
 * refused. A run that fits in the window — the 742 points a 101-repository
 * account costs — is not slowed at all: every reservation comes back due now.
 * <p>
 * The gate is the backstop for when GitHub says to wait anyway, because
 * something else is spending the same token's budget or because its accounting
 * of the window is not ours. One refusal parks every thread, which is the part
 * a per-thread sleep cannot do: ninety threads share one client, so the
 * eighty-nine that were not refused would otherwise keep sending into a limit
 * GitHub has just said is tripped, and the three attempts would be spent on
 * requests that never had a chance.
 */
final class RequestPacer {

	/**
	 * GitHub's documented REST ceiling. Aiming at exactly the published number
	 * rather than under it is deliberate: the gate covers the case where it is
	 * not enough, and a margin picked out of the air would slow every large
	 * account for a guess.
	 */
	static final int POINTS_PER_MINUTE = 900;

	private static final Duration MINUTE = Duration.ofMinutes(1);
	private static final int READ_POINTS = 1;
	private static final String GRAPHQL_PATH = "/graphql";
	private static final int WRITE_POINTS = 5;

	/** What a thread does while it waits; the test clock has its own. */
	interface Sleeper {

		void sleep(long millis) throws InterruptedException;

	}

	private final long windowMillis;
	private final LongSupplier clock;
	private final Sleeper sleeper;

	/**
	 * When each of the last {@code spentAt.length} points was scheduled,
	 * indexed by point number modulo that length. A point may go out once the
	 * one a whole window's worth before it has left the window, which is the
	 * whole of the rule and needs no other state.
	 */
	private final long[] spentAt;

	/** How many points the run has scheduled, ever. */
	private long scheduled;

	/** The instant no request may be sent before — see the class comment. */
	private long gateUntil;

	RequestPacer() {
		this(POINTS_PER_MINUTE, MINUTE);
	}

	RequestPacer(int pointsPerWindow, Duration window) {
		this(pointsPerWindow, window, System::currentTimeMillis, Thread::sleep);
	}

	/**
	 * @param pointsPerWindow at least what one request costs, or a single
	 *                        write's five points would be weighed against slots
	 *                        the same call is about to overwrite.
	 *                        {@link #POINTS_PER_MINUTE} is far past that; a
	 *                        test window is not necessarily.
	 */
	RequestPacer(
			int pointsPerWindow,
			Duration window,
			LongSupplier clock,
			Sleeper sleeper
	) {
		this.spentAt = new long[pointsPerWindow];
		this.windowMillis = window.toMillis();
		this.clock = clock;
		this.sleeper = sleeper;
	}

	/** What one request of this method costs. */
	/**
	 * What a request costs. The method decides it, with one exception: a
	 * GraphQL query is a POST that reads, and drifty sends one per repository —
	 * billed as a write it would pace a run five times harder than the budget
	 * it is actually spending. GitHub meters GraphQL against a separate
	 * allowance again, so one point is the conservative reading of it rather
	 * than the exact one.
	 */
	static int pointsFor(HttpRequest request) {
		if (GRAPHQL_PATH.equals(request.uri().getPath())) {
			return READ_POINTS;
		}
		return switch (request.method()) {
		case "GET", "HEAD", "OPTIONS" -> READ_POINTS;
		default -> WRITE_POINTS;
		};
	}

	/**
	 * Returns once this request's points are due — immediately, unless the
	 * window is full or the gate is up — and answers how long that took, which
	 * is what the caller needs to tell the user a run is being paced on
	 * purpose.
	 * <p>
	 * The gate is re-read after every sleep rather than once at the start,
	 * because a thread parked for a full window is exactly the thread that will
	 * be sending when another one's {@code Retry-After} arrives.
	 */
	long awaitTurn(int points) throws InterruptedException {
		long due = reserve(points);
		long waited = 0;
		for (long wait = waitFor(due); wait > 0; wait = waitFor(due)) {
			sleeper.sleep(wait);
			waited += wait;
		}
		return waited;
	}

	/**
	 * Holds every thread — this one included — off the API for {@code pause}.
	 * Taking the later of the two rather than adding is what keeps ninety
	 * threads reporting the same refusal from compounding it into an hour.
	 */
	synchronized void backOffFor(Duration pause) {
		gateUntil = Math.max(gateUntil, clock.getAsLong() + pause.toMillis());
	}

	/**
	 * Books this request's points and answers when it may go out. The points
	 * are all booked at that one instant, not at the instant each of them came
	 * due, so a write's five points are counted where the request actually
	 * lands.
	 */
	private synchronized long reserve(int points) {
		long at = Math.max(clock.getAsLong(), gateUntil);
		for (int i = 0; i < points; i++) {
			long point = scheduled + i;
			if (point >= spentAt.length) {
				at = Math.max(at, spentAt[slot(point)] + windowMillis);
			}
		}
		for (int i = 0; i < points; i++) {
			spentAt[slot(scheduled + i)] = at;
		}
		scheduled += points;
		return at;
	}

	private synchronized long waitFor(long due) {
		return Math.max(due, gateUntil) - clock.getAsLong();
	}

	private int slot(long point) {
		return (int) (point % spentAt.length);
	}

}
