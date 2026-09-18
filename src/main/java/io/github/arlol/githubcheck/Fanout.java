package io.github.arlol.githubcheck;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.drift.ManagedGroups;

/**
 * One entity's reads, in flight together.
 * <p>
 * A virtual thread per read, on an executor of that entity's own: the bound
 * that matters is {@code GitHubClient}'s semaphore, which every caller's
 * requests share, so bounding threads here as well would only re-serialize what
 * this exists to spread out.
 * <p>
 * A read started here may itself start more — that is what a second level is —
 * so nothing may reject a submission while a task is still running.
 * {@link #close} is therefore the only shutdown, and it happens after the last
 * join.
 * <p>
 * Generic over the group-name enum for the reason {@link ManagedGroups} is: the
 * repository scope and the organization scope name different groups, and
 * neither can use the other's.
 */
final class Fanout<N extends Enum<N>> implements AutoCloseable {

	private final ExecutorService executor = Executors
			.newVirtualThreadPerTaskExecutor();
	private final FetchFailures failures;
	private final ManagedGroups<N> managed;

	Fanout(FetchFailures failures, ManagedGroups<N> managed) {
		this.failures = failures;
		this.managed = managed;
	}

	/**
	 * Sends {@code read} now; the returned supplier is the wait for it.
	 * <p>
	 * For a read that belongs to no group — an entity's own details — or one
	 * whose group its caller has already checked. Everything else goes through
	 * {@link #read}.
	 */
	<T> Supplier<T> start(Supplier<T> read) {
		Future<T> pending = executor.submit(read::get);
		return () -> join(pending);
	}

	/**
	 * Starts one group's read, or hands back {@code fallback} without sending
	 * anything when the group is unmanaged — the same short-circuit the
	 * sequential version got from {@code &&}, kept because an account someone
	 * else administers is exactly where these requests return 403.
	 */
	<T> Supplier<T> read(N group, Supplier<T> read, T fallback) {
		if (!managed.manages(group)) {
			return () -> fallback;
		}
		return start(() -> failures.read(group, read, fallback));
	}

	/**
	 * Rethrows the read's own exception rather than an
	 * {@link ExecutionException} wrapping it: a checker reports
	 * {@code e.getMessage()}, and an entity whose read 403s has to say so
	 * rather than "java.util.concurrent.ExecutionException".
	 * <p>
	 * Anything that is not already a {@link GitHubApiException} becomes one,
	 * keeping its message and cause. That is what the sequential version did
	 * with a checked exception anyway, and it means a bug in one entity's read
	 * ends that entity's entry rather than the whole run — {@code checkOne} has
	 * caught this type since issue #135. An {@link Error} is not wrapped: it is
	 * not this run's to report.
	 */
	private static <T> T join(Future<T> pending) {
		try {
			return pending.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GitHubApiException("Interrupted while reading state", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GitHubApiException failed) {
				throw failed;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new GitHubApiException(cause.getMessage(), cause);
		}
	}

	@Override
	public void close() {
		executor.close();
	}

}
