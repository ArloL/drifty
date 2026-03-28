package io.github.arlol.githubcheck;

import java.util.List;

public record CheckResult(
		List<RepoCheckResult> repos
) {

	public CheckResult {
		repos = List.copyOf(repos);
	}

	public record RepoCheckResult(
			String name,
			Status status,
			List<String> diffs,
			String error
	) {

		public RepoCheckResult {
			diffs = List.copyOf(diffs);
		}

		public static RepoCheckResult ok(String name) {
			return new RepoCheckResult(name, Status.OK, List.of(), null);
		}

		public static RepoCheckResult drift(String name, List<String> diffs) {
			return new RepoCheckResult(name, Status.DRIFT, diffs, null);
		}

		public static RepoCheckResult error(String name, String error) {
			return new RepoCheckResult(name, Status.ERROR, List.of(), error);
		}

		public static RepoCheckResult unknown(String name) {
			return new RepoCheckResult(name, Status.UNKNOWN, List.of(), null);
		}

		public static RepoCheckResult missing(String name) {
			return new RepoCheckResult(name, Status.MISSING, List.of(), null);
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

	public boolean hasDrift() {
		return driftCount() > 0 || errorCount() > 0;
	}

}
