package io.github.arlol.githubcheck;

import java.util.List;

public record CheckResult(
		List<RepoCheckResult> repos
) {

	public CheckResult {
		repos = List.copyOf(repos);
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

	public record RepoCheckResult(
			String name,
			Status status,
			List<String> diffs,
			List<String> fixPreview,
			List<FixReport> fixReports,
			String error
	) {

		public RepoCheckResult {
			diffs = List.copyOf(diffs);
			fixPreview = List.copyOf(fixPreview);
			fixReports = List.copyOf(fixReports);
		}

		public static RepoCheckResult ok(String name) {
			return new RepoCheckResult(
					name,
					Status.OK,
					List.of(),
					List.of(),
					List.of(),
					null
			);
		}

		public static RepoCheckResult drift(String name, List<String> diffs) {
			return drift(name, diffs, List.of());
		}

		public static RepoCheckResult drift(
				String name,
				List<String> diffs,
				List<String> fixPreview
		) {
			return new RepoCheckResult(
					name,
					Status.DRIFT,
					diffs,
					fixPreview,
					List.of(),
					null
			);
		}

		/**
		 * The result of a {@code --fix} run: OK when nothing was left unfixed,
		 * DRIFT otherwise, carrying a FIXED/FAILED line per setting either way.
		 */
		public static RepoCheckResult fixed(
				String name,
				List<String> remainingDiffs,
				List<FixReport> fixReports
		) {
			return new RepoCheckResult(
					name,
					remainingDiffs.isEmpty() ? Status.OK : Status.DRIFT,
					remainingDiffs,
					List.of(),
					fixReports,
					null
			);
		}

		public static RepoCheckResult error(String name, String error) {
			return new RepoCheckResult(
					name,
					Status.ERROR,
					List.of(),
					List.of(),
					List.of(),
					error
			);
		}

		public static RepoCheckResult unknown(String name) {
			return new RepoCheckResult(
					name,
					Status.UNKNOWN,
					List.of(),
					List.of(),
					List.of(),
					null
			);
		}

		public static RepoCheckResult missing(String name) {
			return new RepoCheckResult(
					name,
					Status.MISSING,
					List.of(),
					List.of(),
					List.of(),
					null
			);
		}

	}

	public enum Status {
		OK, DRIFT, ERROR, UNKNOWN, MISSING
	}

	public long okCount() {
		return repos.stream().filter(r -> r.status() == Status.OK).count();
	}

	public long driftCount() {
		return repos.stream().filter(r -> r.status() == Status.DRIFT).count();
	}

	public long errorCount() {
		return repos.stream().filter(r -> r.status() == Status.ERROR).count();
	}

	public long unknownCount() {
		return repos.stream().filter(r -> r.status() == Status.UNKNOWN).count();
	}

	public long missingCount() {
		return repos.stream().filter(r -> r.status() == Status.MISSING).count();
	}

	public boolean hasDrift() {
		return driftCount() > 0 || errorCount() > 0 || missingCount() > 0;
	}

	/**
	 * Every failed fix across all repositories, for the end-of-run summary
	 * SPEC.md requires.
	 */
	public List<String> fixFailures() {
		return repos.stream()
				.flatMap(
						repo -> repo.fixReports()
								.stream()
								.filter(report -> !report.fixed())
								.map(
										report -> repo.name() + ": "
												+ report.message()
								)
				)
				.toList();
	}

}
