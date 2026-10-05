package io.github.arlol.githubcheck;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;

/**
 * The plan of each account a run reads, asked once per account and only by the
 * repository that needs it.
 * <p>
 * The plan is what says a private repository can have no wiki — GitHub offers
 * wikis on private repositories only on paid plans — and no repository response
 * carries it. GitHub shows it to the account itself through {@code GET /user}
 * and to an organization's owners through {@code GET /orgs/{org}}; to anyone
 * else it is absent, and an absent plan changes nothing, which is how every
 * repository was compared before.
 * <p>
 * Every repository of an account shares one read, and the first repository that
 * asks sends it: a run whose private repositories all have the wiki they want
 * sends none.
 */
final class AccountPlans {

	private static final String AUTHENTICATED_USER = "";

	private final GitHubClient client;
	private final Map<String, FutureTask<Optional<Plan>>> plans = new ConcurrentHashMap<>();

	AccountPlans(GitHubClient client) {
		this.client = client;
	}

	/**
	 * Why a private repository of {@code owner} can have no wiki, or
	 * {@code null} when it can or the plan could not be read.
	 */
	@Nullable
	String privateWikiUnavailable(String owner, boolean organizationOwned) {
		Optional<String> plan = organizationOwned ? organizationPlan(owner)
				: userPlan(owner);
		if (plan.filter("free"::equals).isEmpty()) {
			return null;
		}
		return "a private repository has a wiki only on a paid plan, and "
				+ owner + " is on GitHub Free";
	}

	private Optional<String> organizationPlan(String org) {
		return once(
				"org:" + org.toLowerCase(Locale.ROOT),
				() -> client.getOrganization(org)
						.flatMap(
								response -> Optional.ofNullable(response.plan())
										.map(plan -> new Plan(org, plan.name()))
						)
		).map(Plan::name);
	}

	/**
	 * A user's plan, which GitHub shows only to that user: for any other
	 * personal account the token can administer a repository of, there is
	 * nothing to read.
	 */
	private Optional<String> userPlan(String login) {
		return once(AUTHENTICATED_USER, () -> {
			var user = client.getAuthenticatedUserPlan();
			return Optional.ofNullable(user.plan())
					.map(plan -> new Plan(user.login(), plan.name()));
		}).filter(plan -> plan.login().equalsIgnoreCase(login)).map(Plan::name);
	}

	/**
	 * Reads {@code key}'s plan once, whichever repository asks first; the rest
	 * wait for that read. A failed read is no plan: the comparison it feeds
	 * falls back to the one it made before plans were read, and a repository's
	 * check is not the place to report an account-level 403.
	 */
	private Optional<Plan> once(String key, PlanRead read) {
		var task = new FutureTask<Optional<Plan>>(() -> {
			try {
				return read.get();
			} catch (GitHubApiException e) {
				return Optional.empty();
			}
		});
		var existing = plans.putIfAbsent(key, task);
		if (existing == null) {
			task.run();
			existing = task;
		}
		try {
			return existing.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (ExecutionException e) {
			return Optional.empty();
		}
	}

	/** The account a plan was read for, and the plan's name in lower case. */
	private record Plan(
			String login,
			String name
	) {

		Plan {
			name = name.toLowerCase(Locale.ROOT);
		}

	}

	@FunctionalInterface
	private interface PlanRead {

		Optional<Plan> get();

	}

}
