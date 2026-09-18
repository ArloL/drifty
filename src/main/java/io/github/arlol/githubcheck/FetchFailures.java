package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import io.github.arlol.githubcheck.client.GitHubApiException;

/**
 * What a checker does when one group's read fails.
 * <p>
 * {@link #STRICT} rethrows, which is how a check or fix run behaves and has
 * always behaved: a 403 on any group ends the entry with an ERROR, because a
 * comparison against half the state would report drift that is not there.
 * <p>
 * An export wants the opposite. A token without {@code admin:org} should still
 * produce a file, with a comment where the unreadable group would have been —
 * so {@link #collecting} records the failure, hands back the empty value, and
 * the exporter turns each record into a note.
 */
public sealed interface FetchFailures {

	/**
	 * @param group  the drift group whose read failed, as the config names it
	 * @param reason the failure's first line. {@code GitHubApiException}
	 *               carries the whole response body, and a JSON blob does not
	 *               belong in a config file.
	 */
	record Failure(
			String group,
			String reason
	) {
	}

	<T> T read(Enum<?> group, Supplier<T> read, T fallback);

	List<Failure> failures();

	FetchFailures STRICT = new Strict();

	static FetchFailures collecting() {
		return new Collecting();
	}

	final class Strict implements FetchFailures {

		@Override
		public <T> T read(Enum<?> group, Supplier<T> read, T fallback) {
			return read.get();
		}

		@Override
		public List<Failure> failures() {
			return List.of();
		}

	}

	/**
	 * Collects rather than rethrows — and does so from several threads at once,
	 * because {@code RepositoryChecker.fetchState} issues one repository's
	 * group reads in parallel. Two things follow from that, and both are
	 * load-bearing: the list is synchronized, and {@link #failures()} orders
	 * what it hands back instead of returning arrival order. An export of an
	 * unchanged account has to produce the identical file twice running, and
	 * arrival order is whichever 403 came back first.
	 */
	final class Collecting implements FetchFailures {

		private final List<Failure> failures = Collections
				.synchronizedList(new ArrayList<>());

		@Override
		public <T> T read(Enum<?> group, Supplier<T> read, T fallback) {
			try {
				return read.get();
			} catch (GitHubApiException e) {
				failures.add(
						new Failure(group.toString(), firstLine(e.getMessage()))
				);
				return fallback;
			}
		}

		@Override
		public List<Failure> failures() {
			synchronized (failures) {
				return failures.stream()
						.sorted(
								Comparator.comparing(Failure::group)
										.thenComparing(Failure::reason)
						)
						.toList();
			}
		}

		/**
		 * Package-visible so {@code ExportRunner} shares this rather than
		 * repeating it for the one failure this class does not wrap: a
		 * repository's own details request, which {@code fetchState} never
		 * routes through {@link #read}.
		 */
		static String firstLine(String message) {
			if (message == null) {
				return "read failed";
			}
			String line = message.lines().findFirst().orElse(message);
			int body = line.indexOf(": {");
			return body < 0 ? line : line.substring(0, body);
		}

	}

}
