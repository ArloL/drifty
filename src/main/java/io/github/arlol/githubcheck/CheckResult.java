package io.github.arlol.githubcheck;

import java.util.List;
import java.util.stream.Stream;

public record CheckResult(
		List<Entry> orgs,
		List<Entry> repos
) {

	public CheckResult {
		orgs = List.copyOf(orgs);
		repos = List.copyOf(repos);
	}

	public static CheckResult ofRepos(List<Entry> repos) {
		return new CheckResult(List.of(), repos);
	}

	/**
	 * The outcome of one attempted fix, as SPEC.md's "FIXED or FAILED with
	 * reason" per-setting output.
	 *
	 * @param path   the drift path the fix covered
	 * @param fixed  whether it succeeded
	 * @param reason why it did not, {@code null} when it did
	 */
	public record FixReport(
			String path,
			boolean fixed,
			String reason
	) {

		public String message() {
			return fixed ? path + ": FIXED"
					: path + ": FAILED (" + reason + ")";
		}

	}

	/**
	 * @param unmanaged the drift groups this entry leaves alone, named but not
	 *                  valued: their actual values were never fetched, so there
	 *                  is nothing to print for them
	 */
	public record Entry(
			String name,
			Status status,
			List<String> diffs,
			List<String> fixPreview,
			List<FixReport> fixReports,
			String error,
			List<String> unmanaged
	) {

		public Entry {
			diffs = List.copyOf(diffs);
			fixPreview = List.copyOf(fixPreview);
			fixReports = List.copyOf(fixReports);
			unmanaged = List.copyOf(unmanaged);
		}

		public static Entry ok(String name) {
			return ok(name, List.of());
		}

		public static Entry ok(String name, List<String> unmanaged) {
			return new Entry(
					name,
					Status.OK,
					List.of(),
					List.of(),
					List.of(),
					null,
					unmanaged
			);
		}

		public static Entry drift(String name, List<String> diffs) {
			return drift(name, diffs, List.of());
		}

		public static Entry drift(
				String name,
				List<String> diffs,
				List<String> fixPreview
		) {
			return drift(name, diffs, fixPreview, List.of());
		}

		public static Entry drift(
				String name,
				List<String> diffs,
				List<String> fixPreview,
				List<String> unmanaged
		) {
			return new Entry(
					name,
					Status.DRIFT,
					diffs,
					fixPreview,
					List.of(),
					null,
					unmanaged
			);
		}

		/**
		 * The result of a {@code --fix} run: OK when nothing was left unfixed,
		 * DRIFT otherwise, carrying a FIXED/FAILED line per setting either way.
		 * <p>
		 * No unmanaged list: a fix run prints what it applied, and naming the
		 * groups it never touched says nothing about that.
		 */
		public static Entry fixed(
				String name,
				List<String> remainingDiffs,
				List<FixReport> fixReports
		) {
			return new Entry(
					name,
					remainingDiffs.isEmpty() ? Status.OK : Status.DRIFT,
					remainingDiffs,
					List.of(),
					fixReports,
					null,
					List.of()
			);
		}

		public static Entry error(String name, String error) {
			return new Entry(
					name,
					Status.ERROR,
					List.of(),
					List.of(),
					List.of(),
					error,
					List.of()
			);
		}

		public static Entry unknown(String name) {
			return new Entry(
					name,
					Status.UNKNOWN,
					List.of(),
					List.of(),
					List.of(),
					null,
					List.of()
			);
		}

		public static Entry missing(String name) {
			return new Entry(
					name,
					Status.MISSING,
					List.of(),
					List.of(),
					List.of(),
					null,
					List.of()
			);
		}

	}

	public enum Status {
		OK, DRIFT, ERROR, UNKNOWN, MISSING
	}

	private Stream<Entry> all() {
		return Stream.concat(orgs.stream(), repos.stream());
	}

	public long okCount() {
		return repos.stream().filter(r -> r.status() == Status.OK).count();
	}

	public long orgOkCount() {
		return orgs.stream().filter(r -> r.status() == Status.OK).count();
	}

	public long driftCount() {
		return repos.stream().filter(r -> r.status() == Status.DRIFT).count();
	}

	public long orgDriftCount() {
		return orgs.stream().filter(r -> r.status() == Status.DRIFT).count();
	}

	public long errorCount() {
		return repos.stream().filter(r -> r.status() == Status.ERROR).count();
	}

	public long orgErrorCount() {
		return orgs.stream().filter(r -> r.status() == Status.ERROR).count();
	}

	public long unknownCount() {
		return repos.stream().filter(r -> r.status() == Status.UNKNOWN).count();
	}

	public long missingCount() {
		return repos.stream().filter(r -> r.status() == Status.MISSING).count();
	}

	public long orgMissingCount() {
		return orgs.stream().filter(r -> r.status() == Status.MISSING).count();
	}

	public boolean hasDrift() {
		return all().anyMatch(
				entry -> entry.status() == Status.DRIFT
						|| entry.status() == Status.ERROR
						|| entry.status() == Status.MISSING
		);
	}

	/**
	 * Every failed fix across all organizations and repositories, for the
	 * end-of-run summary SPEC.md requires.
	 */
	public List<String> fixFailures() {
		return all()
				.flatMap(
						entry -> entry.fixReports()
								.stream()
								.filter(report -> !report.fixed())
								.map(
										report -> entry.name() + ": "
												+ report.message()
								)
				)
				.toList();
	}

}
