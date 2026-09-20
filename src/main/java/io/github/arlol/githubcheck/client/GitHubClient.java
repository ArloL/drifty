package io.github.arlol.githubcheck.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import net.jcip.annotations.Immutable;

@Immutable
public class GitHubClient {

	private static final String PATH_VULNERABILITY_ALERTS = "/vulnerability-alerts";
	private static final String PATH_AUTOMATED_SECURITY_FIXES = "/automated-security-fixes";
	private static final String PATH_IMMUTABLE_RELEASES = "/immutable-releases";
	private static final String PATH_PRIVATE_VULNERABILITY_REPORTING = "/private-vulnerability-reporting";
	private static final String PATH_CODE_SCANNING_DEFAULT_SETUP = "/code-scanning/default-setup";

	/**
	 * What GitHub documents as the most concurrent requests one account may
	 * have. Measured higher — 120 simultaneous requests were all answered and
	 * 150 drew refusals — but the documented number is what drifty asks for and
	 * the most {@code GitHubCheck} accepts being told to use.
	 */
	public static final int CONCURRENCY_CEILING = 100;

	/**
	 * GitHub answers over one HTTP/2 connection, and its
	 * SETTINGS_MAX_CONCURRENT_STREAMS is 100. The JDK client does not queue
	 * past that — {@code reserveStream0} throws {@code IOException: too many
	 * concurrent streams} — so the bound has to be ours. It sits here rather
	 * than around the per-repository threads because every caller's requests
	 * share the one connection.
	 * <p>
	 * 100 is therefore the ceiling, and drifty asks for all of it. It spent a
	 * while at 90 — room under the limit for anything else using the same token
	 * — and that margin costs 12%: interleaved three times on a 101-repository
	 * account, 90 permits averaged 2.23s and 100 averaged 1.97s with nothing
	 * refused. A token drifty shares is the case that wants the margin back,
	 * and {@code --max-concurrent-requests} is how it is asked for.
	 * <p>
	 * The number only started to matter once
	 * {@code RepositoryChecker.fetchState} stopped issuing one repository's
	 * requests in series: at 50 permits and a 24-request chain the semaphore
	 * was never the limit, and raising it alone bought 9.5s → 7.9s and nothing
	 * more.
	 */
	public static final int MAX_CONCURRENT_REQUESTS = CONCURRENCY_CEILING;

	/**
	 * How many times one request is re-sent after a rate limit rejected it. A
	 * secondary limit says nothing about the request itself, so handing the
	 * rejection to the caller reports a group as unreadable that was never
	 * read. Three attempts covers the ~60s windows GitHub documents without
	 * turning a token that is genuinely out of budget into a run with no end.
	 */
	private static final int RATE_LIMIT_ATTEMPTS = 3;

	/**
	 * What to wait when GitHub says it is a rate limit but not for how long.
	 * Its own guidance for a secondary limit with no {@code Retry-After} is at
	 * least a minute.
	 */
	private static final Duration UNTIMED_RATE_LIMIT_PAUSE = Duration
			.ofSeconds(60);

	/**
	 * How close two pauses have to be to count as the same one for reporting.
	 * Every thread holding a permit hits the same reset within milliseconds of
	 * the others, and one line per permit says no more than one.
	 */
	private static final long PAUSE_REPORTED_WITHIN_MILLIS = 5_000;

	/**
	 * How long a request has to be held back by the pacer before the run says
	 * so. Near the edge of the window a thread waits milliseconds for the point
	 * ahead of it to age out, which is not news; a wait of this order means the
	 * secondary limit, not the network, is what the run is spending its time
	 * on.
	 */
	private static final long PACING_REPORTED_OVER_MILLIS = 1_000;

	/** The {@code page} query parameter of a {@code Link} header URL. */
	private static final Pattern PAGE_PARAM = Pattern
			.compile("([?&]page=)(\\d+)");

	/** The {@code per_page} query parameter of a listing request URL. */
	private static final Pattern PER_PAGE_PARAM = Pattern
			.compile("[?&]per_page=(\\d+)");

	private static final String HEADER_CONTENT_TYPE = "Content-Type";
	private static final String MEDIA_TYPE_JSON = "application/json";
	private static final String HEADER_IF_NONE_MATCH = "If-None-Match";

	// ─── Client
	// ──────────────────────────────────────────────────────────────

	private final String baseUrl;
	private final String token;
	private final HttpClient http;
	private final ObjectMapper mapper;
	private final Semaphore inFlight;
	/**
	 * A final reference, but not an immutable object: {@link #get} writes to it
	 * on every request. {@code DriftyState} is what backs it, and its own
	 * fields are a {@code ConcurrentHashMap} for the same reason
	 * {@link #inFlight} and {@link #reportedPauseEnd} are mutable — every
	 * thread the checkers run shares it.
	 */
	private final ResponseCache cache;
	/**
	 * What keeps the run inside the secondary limits. Mutable for the reason
	 * {@link #inFlight} is: the schedule it hands out is shared by every thread
	 * the checkers run, and a gate one of them raises has to hold the rest.
	 */
	private final RequestPacer pacer;
	/**
	 * When the pause this client last reported ends — see
	 * {@link #PAUSE_REPORTED_WITHIN_MILLIS}. Mutable, unlike everything else
	 * here, for the same reason {@link #inFlight} is: what it counts is shared
	 * by every thread the checkers run.
	 */
	private final AtomicLong reportedPauseEnd = new AtomicLong();

	/** Whether the run has already said that it is pacing itself. */
	private final AtomicBoolean reportedPacing = new AtomicBoolean();

	public GitHubClient(String token) {
		this("https://api.github.com", token);
	}

	public GitHubClient(String token, ResponseCache cache) {
		this(token, cache, MAX_CONCURRENT_REQUESTS);
	}

	public GitHubClient(
			String token,
			ResponseCache cache,
			int maxConcurrentRequests
	) {
		this("https://api.github.com", token, maxConcurrentRequests, cache);
	}

	public GitHubClient(String baseUrl, String token) {
		this(baseUrl, token, ResponseCache.NONE);
	}

	public GitHubClient(String baseUrl, String token, ResponseCache cache) {
		this(baseUrl, token, MAX_CONCURRENT_REQUESTS, cache);
	}

	GitHubClient(String baseUrl, String token, int maxConcurrentRequests) {
		this(baseUrl, token, maxConcurrentRequests, ResponseCache.NONE);
	}

	GitHubClient(
			String baseUrl,
			String token,
			int maxConcurrentRequests,
			ResponseCache cache
	) {
		this(baseUrl, token, maxConcurrentRequests, cache, new RequestPacer());
	}

	GitHubClient(
			String baseUrl,
			String token,
			int maxConcurrentRequests,
			ResponseCache cache,
			RequestPacer pacer
	) {
		this.baseUrl = baseUrl;
		this.token = token;
		this.inFlight = new Semaphore(maxConcurrentRequests);
		this.cache = cache;
		this.pacer = pacer;
		this.http = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_2)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
		this.mapper = new ObjectMapper()
				.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.configure(
						DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
						false
				)
				.configure(
						DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
						true
				);
	}

	// ─── Public API
	// ──────────────────────────────────────────────────────────

	/**
	 * Opens the connection the whole run shares, before there is a request to
	 * send on it.
	 * <p>
	 * A check starts a hundred requests at once and every one of them waits out
	 * DNS, TCP and TLS on the connection they share: traced on a 101-repository
	 * account the first wave answered in 413ms against the 237ms the rest of
	 * the run saw. Sending anything at all first moves that handshake off the
	 * critical path as far as whatever the caller does next will cover —
	 * measured at ~20ms against a config that evaluates in 30ms, and worth more
	 * against a larger one.
	 * <p>
	 * Unauthenticated and discarded: {@code HEAD /} costs the token nothing and
	 * the connection is pooled by host, not by credentials. Nothing waits for
	 * it and a failure is not reported — the connection a run needs is opened
	 * by its first real request either way, and that request is where an
	 * unreachable host has always been reported.
	 */
	public void warmUp() {
		Thread.ofVirtual().name("drifty-connect").start(() -> {
			try {
				http.send(
						HttpRequest.newBuilder(URI.create(baseUrl + "/"))
								.method(
										"HEAD",
										HttpRequest.BodyPublishers.noBody()
								)
								.build(),
						HttpResponse.BodyHandlers.discarding()
				);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (IOException e) {
				// See above: the first real request reports it.
			}
		});
	}

	/**
	 * One repository's rulesets, branch protections, collaborators and
	 * vulnerability-alerts flag, in one request.
	 * <p>
	 * Over REST those are six: a ruleset listing and a request per ruleset, a
	 * branch listing and a request per protected branch, the collaborators and
	 * the alerts flag. Traced on a 101-repository account, 268 of a check's 742
	 * requests and both of a repository's two-level chains. GraphQL answers all
	 * of it in one round trip of its own — ~0.56s against the ~0.25s a REST
	 * read costs, which is the trade that pays.
	 * <p>
	 * One query per repository, never one for several: nothing a repository is
	 * read for needs another repository, and a batch would be the first thing
	 * that did.
	 */
	public GraphQlRepositoryResponse graphqlRepository(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = post(
				baseUrl + "/graphql",
				writeValue(
						Map.of("query", GraphQlQuery.repository(owner, repo))
				)
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " querying " + owner + "/"
							+ repo + ": " + resp.body()
			);
		}
		return GraphQlQuery.read(readTree(resp.body()));
	}

	/**
	 * The organization's repositories, or empty when GitHub does not know the
	 * organization. The empty result is what makes the org report MISSING
	 * rather than error.
	 */
	public Optional<List<RepositorySummaryResponse>> listOrgRepos(String org) {
		String url = baseUrl + "/orgs/" + org + "/repos?per_page=100&type=all";
		HttpResponse<String> resp = get(url);
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + org
							+ ": " + resp.body()
			);
		}
		return Optional.of(summaries(resp));
	}

	/**
	 * A personal account's repositories. {@code /users/{login}/repos} returns
	 * only public ones, so this reads {@code /user/repos}, which covers public,
	 * private and archived — for the authenticated user, which is the only
	 * personal account a token can manage.
	 */
	public List<RepositorySummaryResponse> listUserRepos(String login) {
		return listUserReposPaged(login).all();
	}

	/**
	 * The same listing, asked for on a virtual thread so the caller can be
	 * reading repositories while it arrives.
	 * <p>
	 * The config names every repository a check reads, so the listing is not
	 * what says which ones to start — it answers what GitHub has that the
	 * config does not declare, and what it declares that GitHub does not have.
	 * Waiting for it was 596ms of a traced 3.09s fetch with fewer than ten
	 * requests in flight.
	 * <p>
	 * The thread runs to completion whether or not anyone joins it, and a
	 * listing that failed throws out of the supplier rather than out of this.
	 */
	public Supplier<List<RepositorySummaryResponse>> listUserReposAsync(
			String login
	) {
		var listing = new FutureTask<>(() -> listUserRepos(login));
		Thread.ofVirtual().name("drifty-listing").start(listing);
		return () -> await(listing);
	}

	/**
	 * The same listing, with its first page handed back before the pages after
	 * it have arrived — see {@link PagedRepositories}.
	 */
	private PagedRepositories listUserReposPaged(String login) {
		HttpResponse<String> resp = get(
				baseUrl + "/user/repos?per_page=100&type=owner"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + login
							+ ": " + resp.body()
			);
		}
		return paged(resp);
	}

	/**
	 * The first page's repositories, with every page after it already in
	 * flight.
	 * <p>
	 * The thread runs to completion whether or not anyone joins it, so the
	 * pages it asks for are spent either way; every caller does join, through
	 * {@link PagedRepositories#all()} or the checker. Its requests are bounded
	 * by the same semaphore as everything else.
	 */
	private PagedRepositories paged(HttpResponse<String> firstResp) {
		var rest = new FutureTask<>(
				() -> toSummaries(remainingItems(firstResp))
		);
		Thread.ofVirtual().name("drifty-listing-pages").start(rest);
		return new PagedRepositories(
				toSummaries(arrayItems(firstResp, null)),
				() -> await(rest)
		);
	}

	/**
	 * A repository listing whose first page is in hand while the rest of it is
	 * still arriving.
	 * <p>
	 * Every page after the first already goes out at once, but the first one
	 * has to answer before any of them can be asked for, and the whole listing
	 * used to answer before any repository was looked at: for a 101-repository
	 * account that was the first 1.24s of a 4.45s check with the request
	 * semaphore idle. A caller that has no use for the split calls
	 * {@link #all()} and sees what it always saw.
	 *
	 * @param firstPage the repositories page one named
	 * @param rest      every repository after them, joined when asked for
	 */
	private record PagedRepositories(
			List<RepositorySummaryResponse> firstPage,
			Supplier<List<RepositorySummaryResponse>> rest
	) {

		private PagedRepositories {
			firstPage = List.copyOf(firstPage);
		}

		private List<RepositorySummaryResponse> all() {
			var all = new ArrayList<>(firstPage);
			all.addAll(rest.get());
			return List.copyOf(all);
		}

	}

	private List<RepositorySummaryResponse> summaries(
			HttpResponse<String> resp
	) {
		return toSummaries(collectPaginatedArrayItems(resp, null));
	}

	private List<RepositorySummaryResponse> toSummaries(List<JsonNode> items) {
		return items.stream()
				.map(
						node -> mapper.convertValue(
								node,
								RepositorySummaryResponse.class
						)
				)
				.toList();
	}

	public RepositoryDetailsResponse getRepo(String owner, String repo) {
		HttpResponse<String> resp = get(repoUrl(owner, repo));
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " fetching repo " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
		return readValue(resp.body(), RepositoryDetailsResponse.class);
	}

	public Optional<ImmutableReleasesResponse> getImmutableReleases(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + PATH_IMMUTABLE_RELEASES
		);
		if (resp.statusCode() == 200) {
			return Optional.of(
					readValue(resp.body(), ImmutableReleasesResponse.class)
			);
		}
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		throw new GitHubApiException(
				"HTTP " + resp.statusCode() + " GET immutable-releases on "
						+ repo
		);
	}

	public List<BranchResponse> getBranches(String owner, String repo) {
		return getBranches(owner, repo, false);
	}

	public List<BranchResponse> getBranches(
			String owner,
			String repo,
			boolean isProtected
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/branches?per_page=100&protected="
						+ isProtected
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET branches on " + repo
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(e -> mapper.convertValue(e, BranchResponse.class))
				.toList();
	}

	public List<Secret> getActionSecrets(String owner, String repo) {
		String url = repoUrl(owner, repo) + "/actions/secrets?per_page=100";
		HttpResponse<String> resp = get(url);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for action secrets on "
							+ repo + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "secrets").stream()
				.map(s -> mapper.convertValue(s, Secret.class))
				.toList();
	}

	public Secret getActionSecret(String owner, String repo, String name) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/actions/secrets/" + name
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET action secret " + name
							+ " on " + repo + ": " + resp.body()
			);
		}
		return readValue(resp.body(), Secret.class);
	}

	// ─── Webhooks
	// ──────────────────────────────────────────────────────────

	public List<WebhookResponse> getRepoWebhooks(String owner, String repo) {
		return webhooks(
				repoUrl(owner, repo) + "/hooks",
				"webhooks on " + owner + "/" + repo
		);
	}

	public List<WebhookResponse> getOrgWebhooks(String org) {
		return webhooks(orgUrl(org) + "/hooks", "webhooks on " + org);
	}

	private List<WebhookResponse> webhooks(String url, String what) {
		HttpResponse<String> resp = get(url + "?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for " + what + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(h -> mapper.convertValue(h, WebhookResponse.class))
				.toList();
	}

	public WebhookResponse createRepoWebhook(
			String owner,
			String repo,
			WebhookRequest hook
	) {
		return createWebhook(
				repoUrl(owner, repo) + "/hooks",
				hook,
				"webhook on " + owner + "/" + repo
		);
	}

	public WebhookResponse createOrgWebhook(String org, WebhookRequest hook) {
		return createWebhook(orgUrl(org) + "/hooks", hook, "webhook on " + org);
	}

	private WebhookResponse createWebhook(
			String url,
			WebhookRequest hook,
			String what
	) {
		HttpResponse<String> resp = post(url, writeValue(hook));
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating " + what + ": "
							+ resp.body()
			);
		}
		return readValue(resp.body(), WebhookResponse.class);
	}

	public WebhookResponse updateRepoWebhook(
			String owner,
			String repo,
			long hookId,
			WebhookRequest hook
	) {
		return updateWebhook(
				repoUrl(owner, repo) + "/hooks/" + hookId,
				hook,
				"webhook " + hookId + " on " + owner + "/" + repo
		);
	}

	public WebhookResponse updateOrgWebhook(
			String org,
			long hookId,
			WebhookRequest hook
	) {
		return updateWebhook(
				orgUrl(org) + "/hooks/" + hookId,
				hook,
				"webhook " + hookId + " on " + org
		);
	}

	private WebhookResponse updateWebhook(
			String url,
			WebhookRequest hook,
			String what
	) {
		HttpResponse<String> resp = patch(url, writeValue(hook));
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating " + what + ": "
							+ resp.body()
			);
		}
		return readValue(resp.body(), WebhookResponse.class);
	}

	public void deleteRepoWebhook(String owner, String repo, long hookId) {
		deleteWebhook(
				repoUrl(owner, repo) + "/hooks/" + hookId,
				"webhook " + hookId + " on " + owner + "/" + repo
		);
	}

	public void deleteOrgWebhook(String org, long hookId) {
		deleteWebhook(
				orgUrl(org) + "/hooks/" + hookId,
				"webhook " + hookId + " on " + org
		);
	}

	private void deleteWebhook(String url, String what) {
		HttpResponse<String> resp = delete(url);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting " + what + ": "
							+ resp.body()
			);
		}
	}

	// ─── Actions repository selection and runner groups
	// ──────────────────────────────────────────────────────────

	/** The repositories Actions is enabled in under {@code selected}. */
	public List<RepositorySummaryResponse> getOrgActionsPermissionsRepositories(
			String org
	) {
		return selectedRepositories(
				orgUrl(org) + "/actions/permissions/repositories",
				"Actions-enabled repositories of " + org
		);
	}

	public void setOrgActionsPermissionsRepositories(
			String org,
			List<Long> repositoryIds
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/permissions/repositories",
				writeValue(new SelectedRepositoryIdsRequest(repositoryIds))
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " setting Actions-enabled repositories of " + org
							+ ": " + resp.body()
			);
		}
	}

	private String runnerGroupsUrl(String org) {
		return orgUrl(org) + "/actions/runner-groups";
	}

	public List<RunnerGroupResponse> listRunnerGroups(String org) {
		HttpResponse<String> resp = get(runnerGroupsUrl(org) + "?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing runner groups of "
							+ org + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "runner_groups").stream()
				.map(g -> mapper.convertValue(g, RunnerGroupResponse.class))
				.toList();
	}

	public List<RepositorySummaryResponse> getRunnerGroupRepositories(
			String org,
			long groupId
	) {
		return selectedRepositories(
				runnerGroupsUrl(org) + "/" + groupId + "/repositories",
				"repositories of runner group " + groupId + " on " + org
		);
	}

	private List<RepositorySummaryResponse> selectedRepositories(
			String url,
			String what
	) {
		HttpResponse<String> resp = get(url + "?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for " + what + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "repositories").stream()
				.map(
						r -> mapper.convertValue(
								r,
								RepositorySummaryResponse.class
						)
				)
				.toList();
	}

	public RunnerGroupResponse createRunnerGroup(
			String org,
			RunnerGroupRequest group
	) {
		HttpResponse<String> resp = post(
				runnerGroupsUrl(org),
				writeValue(group)
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating runner group "
							+ group.name() + " on " + org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), RunnerGroupResponse.class);
	}

	public void updateRunnerGroup(
			String org,
			long groupId,
			RunnerGroupRequest group
	) {
		HttpResponse<String> resp = patch(
				runnerGroupsUrl(org) + "/" + groupId,
				writeValue(group)
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating runner group "
							+ groupId + " on " + org + ": " + resp.body()
			);
		}
	}

	public void setRunnerGroupRepositories(
			String org,
			long groupId,
			List<Long> repositoryIds
	) {
		HttpResponse<String> resp = put(
				runnerGroupsUrl(org) + "/" + groupId + "/repositories",
				writeValue(new SelectedRepositoryIdsRequest(repositoryIds))
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " setting repositories of runner group " + groupId
							+ " on " + org + ": " + resp.body()
			);
		}
	}

	public void deleteRunnerGroup(String org, long groupId) {
		HttpResponse<String> resp = delete(
				runnerGroupsUrl(org) + "/" + groupId
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting runner group "
							+ groupId + " on " + org + ": " + resp.body()
			);
		}
	}

	// ─── Collaborators, teams and members
	// ──────────────────────────────────────────────────────────

	/** Adds or updates a collaborator; 201 invites, 204 updates. */
	public void addCollaborator(
			String owner,
			String repo,
			String login,
			String permission
	) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + "/collaborators/" + login,
				writeValue(new PermissionRequest(permission))
		);
		if (resp.statusCode() != 201 && resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " adding collaborator "
							+ login + " to " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	public List<RepoTeamResponse> getRepoTeams(String owner, String repo) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/teams?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for teams of " + owner + "/"
							+ repo + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(t -> mapper.convertValue(t, RepoTeamResponse.class))
				.toList();
	}

	public void setTeamRepositoryPermission(
			String org,
			String slug,
			String owner,
			String repo,
			String permission
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/teams/" + slug + "/repos/" + owner + "/" + repo,
				writeValue(new PermissionRequest(permission))
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " granting team " + slug
							+ " access to " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	public List<TeamResponse> listOrgTeams(String org) {
		HttpResponse<String> resp = get(orgUrl(org) + "/teams?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing teams of " + org
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(t -> mapper.convertValue(t, TeamResponse.class))
				.toList();
	}

	/**
	 * The team's members with one role: {@code member} or {@code maintainer}.
	 */
	public List<SimpleUser> getTeamMembers(
			String org,
			String slug,
			String role
	) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/teams/" + slug + "/members?role=" + role
						+ "&per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for members of team " + slug
							+ " in " + org + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(u -> mapper.convertValue(u, SimpleUser.class))
				.toList();
	}

	public TeamResponse createTeam(String org, TeamCreateRequest team) {
		HttpResponse<String> resp = post(
				orgUrl(org) + "/teams",
				writeValue(team)
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating team "
							+ team.name() + " in " + org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), TeamResponse.class);
	}

	public void updateTeam(String org, String slug, TeamRequest team) {
		HttpResponse<String> resp = patch(
				orgUrl(org) + "/teams/" + slug,
				writeValue(team)
		);
		if (resp.statusCode() != 200 && resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating team " + slug
							+ " in " + org + ": " + resp.body()
			);
		}
	}

	public void setTeamMembership(
			String org,
			String slug,
			String login,
			String role
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/teams/" + slug + "/memberships/" + login,
				writeValue(new RoleRequest(role))
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " setting membership of "
							+ login + " in team " + slug + " of " + org + ": "
							+ resp.body()
			);
		}
	}

	/**
	 * The organization's members with one role: {@code admin} or
	 * {@code member}.
	 */
	public List<SimpleUser> listOrgMembers(String org, String role) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/members?role=" + role + "&per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing members of " + org
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(u -> mapper.convertValue(u, SimpleUser.class))
				.toList();
	}

	/** Sets a member's role, or invites a user who is not yet a member. */
	public void setOrgMembership(String org, String login, String role) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/memberships/" + login,
				writeValue(new RoleRequest(role))
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " setting membership of "
							+ login + " in " + org + ": " + resp.body()
			);
		}
	}

	// ─── Code security configurations
	// ──────────────────────────────────────────────────────────

	private String codeSecurityUrl(String org) {
		return orgUrl(org) + "/code-security/configurations";
	}

	public List<CodeSecurityConfigurationResponse> getCodeSecurityConfigurations(
			String org
	) {
		HttpResponse<String> resp = get(codeSecurityUrl(org) + "?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " for code security configurations of " + org
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(
						c -> mapper.convertValue(
								c,
								CodeSecurityConfigurationResponse.class
						)
				)
				.toList();
	}

	public List<CodeSecurityDefaultResponse> getCodeSecurityDefaults(
			String org
	) {
		HttpResponse<String> resp = get(codeSecurityUrl(org) + "/defaults");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " for code security defaults of " + org + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(
						d -> mapper.convertValue(
								d,
								CodeSecurityDefaultResponse.class
						)
				)
				.toList();
	}

	public List<CodeSecurityRepositoryResponse> getCodeSecurityConfigurationRepositories(
			String org,
			long configurationId
	) {
		HttpResponse<String> resp = get(
				codeSecurityUrl(org) + "/" + configurationId
						+ "/repositories?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " for repositories of code security configuration "
							+ configurationId + " on " + org + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(
						r -> mapper.convertValue(
								r,
								CodeSecurityRepositoryResponse.class
						)
				)
				.toList();
	}

	public CodeSecurityConfigurationResponse createCodeSecurityConfiguration(
			String org,
			CodeSecurityConfigurationRequest configuration
	) {
		HttpResponse<String> resp = post(
				codeSecurityUrl(org),
				writeValue(configuration)
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " creating code security configuration on " + org
							+ ": " + resp.body()
			);
		}
		return readValue(resp.body(), CodeSecurityConfigurationResponse.class);
	}

	public void updateCodeSecurityConfiguration(
			String org,
			long configurationId,
			CodeSecurityConfigurationRequest configuration
	) {
		HttpResponse<String> resp = patch(
				codeSecurityUrl(org) + "/" + configurationId,
				writeValue(configuration)
		);
		if (resp.statusCode() != 200 && resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating code security configuration "
							+ configurationId + " on " + org + ": "
							+ resp.body()
			);
		}
	}

	public void setCodeSecurityDefaults(
			String org,
			long configurationId,
			String defaultForNewRepos
	) {
		HttpResponse<String> resp = put(
				codeSecurityUrl(org) + "/" + configurationId + "/defaults",
				writeValue(new CodeSecurityDefaultsRequest(defaultForNewRepos))
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " setting defaults of code security configuration "
							+ configurationId + " on " + org + ": "
							+ resp.body()
			);
		}
	}

	public void attachCodeSecurityConfiguration(
			String org,
			long configurationId,
			List<Long> repositoryIds
	) {
		HttpResponse<String> resp = post(
				codeSecurityUrl(org) + "/" + configurationId + "/attach",
				writeValue(
						new CodeSecurityAttachRequest("selected", repositoryIds)
				)
		);
		if (resp.statusCode() != 202) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " attaching code security configuration "
							+ configurationId + " on " + org + ": "
							+ resp.body()
			);
		}
	}

	// ─── Custom properties
	// ──────────────────────────────────────────────────────────

	public List<CustomPropertyResponse> getOrgCustomProperties(String org) {
		HttpResponse<String> resp = get(orgUrl(org) + "/properties/schema");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for custom properties of "
							+ org + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(p -> mapper.convertValue(p, CustomPropertyResponse.class))
				.toList();
	}

	public void putOrgCustomProperty(
			String org,
			String name,
			CustomPropertyRequest property
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/properties/schema/" + name,
				writeValue(property)
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " writing custom property "
							+ name + " on " + org + ": " + resp.body()
			);
		}
	}

	public List<CustomPropertyValueResponse> getRepoCustomPropertyValues(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/properties/values"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " for custom property values of " + owner + "/"
							+ repo + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(
						p -> mapper.convertValue(
								p,
								CustomPropertyValueResponse.class
						)
				)
				.toList();
	}

	public void updateRepoCustomPropertyValues(
			String owner,
			String repo,
			CustomPropertyValuesRequest values
	) {
		HttpResponse<String> resp = patch(
				repoUrl(owner, repo) + "/properties/values",
				writeValue(values)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " writing custom property values on " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	// ─── Actions variables
	// ──────────────────────────────────────────────────────────

	public List<VariableResponse> getActionVariables(
			String owner,
			String repo
	) {
		return variables(
				repoUrl(owner, repo) + "/actions/variables",
				"action variables on " + owner + "/" + repo
		);
	}

	public List<VariableResponse> getEnvironmentVariables(
			String owner,
			String repo,
			String envName
	) {
		return variables(
				environmentUrl(owner, repo, envName) + "/variables",
				"variables of " + envName + " on " + owner + "/" + repo
		);
	}

	private List<VariableResponse> variables(String url, String what) {
		HttpResponse<String> resp = get(url + "?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for " + what + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "variables").stream()
				.map(v -> mapper.convertValue(v, VariableResponse.class))
				.toList();
	}

	public void createActionVariable(
			String owner,
			String repo,
			VariableRequest variable
	) {
		createVariable(
				repoUrl(owner, repo) + "/actions/variables",
				variable,
				"action variable " + variable.name() + " on " + owner + "/"
						+ repo
		);
	}

	public void updateActionVariable(
			String owner,
			String repo,
			VariableRequest variable
	) {
		updateVariable(
				repoUrl(owner, repo) + "/actions/variables/" + variable.name(),
				variable,
				"action variable " + variable.name() + " on " + owner + "/"
						+ repo
		);
	}

	public void createEnvironmentVariable(
			String owner,
			String repo,
			String envName,
			VariableRequest variable
	) {
		createVariable(
				environmentUrl(owner, repo, envName) + "/variables",
				variable,
				"variable " + variable.name() + " of " + envName + " on "
						+ owner + "/" + repo
		);
	}

	public void updateEnvironmentVariable(
			String owner,
			String repo,
			String envName,
			VariableRequest variable
	) {
		updateVariable(
				environmentUrl(owner, repo, envName) + "/variables/"
						+ variable.name(),
				variable,
				"variable " + variable.name() + " of " + envName + " on "
						+ owner + "/" + repo
		);
	}

	private void createVariable(
			String url,
			VariableRequest variable,
			String what
	) {
		HttpResponse<String> resp = post(url, writeValue(variable));
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating " + what + ": "
							+ resp.body()
			);
		}
	}

	private void updateVariable(
			String url,
			VariableRequest variable,
			String what
	) {
		HttpResponse<String> resp = patch(url, writeValue(variable));
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating " + what + ": "
							+ resp.body()
			);
		}
	}

	public List<OrgVariableResponse> getOrgActionVariables(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/variables?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " for org action variables on " + org + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "variables").stream()
				.map(v -> mapper.convertValue(v, OrgVariableResponse.class))
				.toList();
	}

	/** The repositories a {@code selected} variable is shared with. */
	public List<RepositorySummaryResponse> getOrgActionVariableRepositories(
			String org,
			String name
	) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/variables/" + name
						+ "/repositories?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for repositories of org "
							+ "variable " + name + " on " + org + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "repositories").stream()
				.map(
						node -> mapper.convertValue(
								node,
								RepositorySummaryResponse.class
						)
				)
				.toList();
	}

	public void createOrgActionVariable(
			String org,
			OrgVariableRequest variable
	) {
		HttpResponse<String> resp = post(
				orgUrl(org) + "/actions/variables",
				writeValue(variable)
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " creating org action variable " + variable.name()
							+ " on " + org + ": " + resp.body()
			);
		}
	}

	public void updateOrgActionVariable(
			String org,
			OrgVariableRequest variable
	) {
		HttpResponse<String> resp = patch(
				orgUrl(org) + "/actions/variables/" + variable.name(),
				writeValue(variable)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating org action variable " + variable.name()
							+ " on " + org + ": " + resp.body()
			);
		}
	}

	public List<EnvironmentDetailsResponse> getEnvironments(
			String owner,
			String repo
	) {
		String url = repoUrl(owner, repo) + "/environments?per_page=100";
		HttpResponse<String> resp = get(url);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for environments on " + repo
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "environments").stream()
				.map(
						e -> mapper.convertValue(
								e,
								EnvironmentDetailsResponse.class
						)
				)
				.toList();
	}

	public EnvironmentDetailsResponse createOrUpdateEnvironment(
			String owner,
			String repo,
			String envName,
			EnvironmentUpdateRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = put(
				environmentUrl(owner, repo, envName),
				body
		);
		if (resp.statusCode() != 200 && resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " creating/updating environment " + envName
							+ " on " + owner + "/" + repo + ": " + resp.body()
			);
		}
		return readValue(resp.body(), EnvironmentDetailsResponse.class);
	}

	public void updateEnvironment(
			String owner,
			String repo,
			String envName,
			EnvironmentUpdateRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = put(
				environmentUrl(owner, repo, envName),
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating environment "
							+ envName + " on " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	public void deleteEnvironment(String owner, String repo, String envName) {
		HttpResponse<String> resp = delete(
				environmentUrl(owner, repo, envName)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting environment "
							+ envName + " on " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	/**
	 * The custom deployment branch policies of an environment. GitHub answers
	 * 404 unless the environment has {@code custom_branch_policies} on, so the
	 * caller only asks for environments that do.
	 */
	public List<DeploymentBranchPolicyResponse> getDeploymentBranchPolicies(
			String owner,
			String repo,
			String envName
	) {
		HttpResponse<String> resp = get(
				environmentUrl(owner, repo, envName)
						+ "/deployment-branch-policies?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " for deployment branch policies of " + envName
							+ " on " + owner + "/" + repo + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "branch_policies").stream()
				.map(
						node -> mapper.convertValue(
								node,
								DeploymentBranchPolicyResponse.class
						)
				)
				.toList();
	}

	public DeploymentBranchPolicyResponse createDeploymentBranchPolicy(
			String owner,
			String repo,
			String envName,
			DeploymentBranchPolicyRequest payload
	) {
		HttpResponse<String> resp = post(
				environmentUrl(owner, repo, envName)
						+ "/deployment-branch-policies",
				writeValue(payload)
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " creating deployment branch policy "
							+ payload.name() + " for " + envName + " on "
							+ owner + "/" + repo + ": " + resp.body()
			);
		}
		return readValue(resp.body(), DeploymentBranchPolicyResponse.class);
	}

	public void deleteDeploymentBranchPolicy(
			String owner,
			String repo,
			String envName,
			long policyId
	) {
		HttpResponse<String> resp = delete(
				environmentUrl(owner, repo, envName)
						+ "/deployment-branch-policies/" + policyId
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " deleting deployment branch policy " + policyId
							+ " for " + envName + " on " + owner + "/" + repo
							+ ": " + resp.body()
			);
		}
	}

	/** The numeric id behind a login, for endpoints that want ids. */
	public long getUserId(String login) {
		HttpResponse<String> resp = get(baseUrl + "/users/" + login);
		if (resp.statusCode() == 404) {
			throw new GitHubApiException("no user " + login);
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET user " + login + ": "
							+ resp.body()
			);
		}
		return readValue(resp.body(), SimpleUser.class).id();
	}

	/** A team by slug, or empty when the organization has no such team. */
	public Optional<TeamResponse> getTeam(String org, String slug) {
		HttpResponse<String> resp = get(orgUrl(org) + "/teams/" + slug);
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET team " + slug + " on "
							+ org + ": " + resp.body()
			);
		}
		return Optional.of(readValue(resp.body(), TeamResponse.class));
	}

	/** The numeric id behind a team slug, for endpoints that want ids. */
	public long getTeamId(String org, String slug) {
		return getTeam(org, slug).orElseThrow(
				() -> new GitHubApiException("no team " + slug + " in " + org)
		).id();
	}

	public List<Secret> getEnvironmentSecrets(
			String owner,
			String repo,
			String env
	) {
		String url = environmentUrl(owner, repo, env) + "/secrets?per_page=100";
		HttpResponse<String> resp = get(url);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for environment secrets on "
							+ repo + "/" + env + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "secrets").stream()
				.map(s -> mapper.convertValue(s, Secret.class))
				.toList();
	}

	public Secret getEnvironmentSecret(
			String owner,
			String repo,
			String env,
			String name
	) {
		HttpResponse<String> resp = get(
				environmentUrl(owner, repo, env) + "/secrets/" + name
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET env secret " + name
							+ " on " + repo + "/" + env + ": " + resp.body()
			);
		}
		return readValue(resp.body(), Secret.class);
	}

	public SecretPublicKeyResponse getActionSecretPublicKey(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/actions/secrets/public-key"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " GET action secret public key on " + repo
			);
		}
		return readValue(resp.body(), SecretPublicKeyResponse.class);
	}

	public void createOrUpdateActionSecret(
			String owner,
			String repo,
			String secretName,
			String secretValue
	) {
		var publicKey = getActionSecretPublicKey(owner, repo);
		createOrUpdateActionSecret(
				owner,
				repo,
				secretName,
				new SecretRequest(
						Secrets.encryptSecret(publicKey.key(), secretValue),
						publicKey.keyId()
				)
		);
	}

	public void createOrUpdateActionSecret(
			String owner,
			String repo,
			String name,
			SecretRequest request
	) {
		String body = writeValue(request);
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + "/actions/secrets/" + name,
				body
		);
		if (resp.statusCode() != 201 && resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " PUT action secret " + name
							+ " on " + repo
			);
		}
	}

	public SecretPublicKeyResponse getEnvironmentSecretPublicKey(
			String owner,
			String repo,
			String env
	) {
		HttpResponse<String> resp = get(
				environmentUrl(owner, repo, env) + "/secrets/public-key"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " GET env secret public key on " + repo + "/"
							+ env
			);
		}
		return readValue(resp.body(), SecretPublicKeyResponse.class);
	}

	public void createOrUpdateEnvironmentSecret(
			String owner,
			String repo,
			String environmentName,
			String secretName,
			String secretValue
	) {
		var publicKey = getEnvironmentSecretPublicKey(
				owner,
				repo,
				environmentName
		);
		createOrUpdateEnvironmentSecret(
				owner,
				repo,
				environmentName,
				secretName,
				new SecretRequest(
						Secrets.encryptSecret(publicKey.key(), secretValue),
						publicKey.keyId()
				)
		);
	}

	public void createOrUpdateEnvironmentSecret(
			String owner,
			String repo,
			String env,
			String name,
			SecretRequest request
	) {
		String body = writeValue(request);
		HttpResponse<String> resp = put(
				environmentUrl(owner, repo, env) + "/secrets/" + name,
				body
		);
		if (resp.statusCode() != 201 && resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " PUT env secret " + name
							+ " on " + repo + "/" + env
			);
		}
	}

	public WorkflowPermissions getWorkflowPermissions(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/actions/permissions/workflow"
		);
		if (resp.statusCode() == 403) {
			throw new GitHubApiException(
					"HTTP 403 for workflow permissions on " + repo
							+ " — token may lack admin scope"
			);
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " GET workflow permissions on " + repo
			);
		}
		return readValue(resp.body(), WorkflowPermissions.class);
	}

	public void updateWorkflowPermissions(
			String owner,
			String repo,
			WorkflowPermissions permissions
	) {
		String body = writeValue(permissions);
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + "/actions/permissions/workflow",
				body
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating workflow permissions on " + repo
			);
		}
	}

	public BranchProtectionResponse updateBranchProtection(
			String owner,
			String repo,
			String branch,
			BranchProtectionRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = put(
				branchProtectionUrl(owner, repo, branch),
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating branch protection on " + repo
			);
		}
		return readValue(resp.body(), BranchProtectionResponse.class);
	}

	public void deleteBranchProtection(
			String owner,
			String repo,
			String branch
	) {
		HttpResponse<String> resp = delete(
				branchProtectionUrl(owner, repo, branch)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " deleting branch protection on " + owner + "/"
							+ repo + "/" + branch + ": " + resp.body()
			);
		}
	}

	public void updateRepository(
			String owner,
			String repo,
			RepositoryUpdateRequest request
	) {
		String body = writeValue(request);
		HttpResponse<String> resp = patch(repoUrl(owner, repo), body);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating " + owner + "/"
							+ repo + ": " + resp.body()
			);
		}
	}

	public Optional<PagesResponse> getPages(String owner, String repo) {
		HttpResponse<String> resp = get(pagesUrl(owner, repo));
		if (resp.statusCode() == 403) {
			throw new GitHubApiException(
					"HTTP 403 for pages on " + repo
							+ " — token may lack admin scope"
			);
		}
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET pages on " + repo
			);
		}
		return Optional.of(readValue(resp.body(), PagesResponse.class));
	}

	public PagesResponse createPages(
			String owner,
			String repo,
			PagesCreateRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = post(pagesUrl(owner, repo), body);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
		return readValue(resp.body(), PagesResponse.class);
	}

	public void updatePages(
			String owner,
			String repo,
			PagesUpdateRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = put(pagesUrl(owner, repo), body);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	public void deletePages(String owner, String repo) {
		HttpResponse<String> resp = delete(pagesUrl(owner, repo));
		if (resp.statusCode() != 204 && resp.statusCode() != 404) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	public void enableVulnerabilityAlerts(String owner, String repo) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + PATH_VULNERABILITY_ALERTS
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling vulnerability-alerts on " + repo
			);
		}
	}

	/**
	 * Whether Dependabot security updates are on, asked of the endpoint.
	 * <p>
	 * {@code security_and_analysis.dependabot_security_updates} on the
	 * repository's own details is the same bit and costs no request, so
	 * {@code RepositoryStateReader} asks here only where GitHub omits that
	 * section.
	 */
	public boolean getAutomatedSecurityFixes(String owner, String repo) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + PATH_AUTOMATED_SECURITY_FIXES
		);
		if (resp.statusCode() == 200) {
			return readValue(resp.body(), AutomatedSecurityFixesResponse.class)
					.enabled();
		}
		if (resp.statusCode() == 404) {
			return false;
		}
		throw new GitHubApiException(
				"HTTP " + resp.statusCode()
						+ " GET automated-security-fixes on " + repo
		);
	}

	public void enableAutomatedSecurityFixes(String owner, String repo) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + PATH_AUTOMATED_SECURITY_FIXES
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling automated-security-fixes on " + repo
			);
		}
	}

	public void disableVulnerabilityAlerts(String owner, String repo) {
		HttpResponse<String> resp = delete(
				repoUrl(owner, repo) + PATH_VULNERABILITY_ALERTS
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " disabling vulnerability-alerts on " + repo
			);
		}
	}

	public void disableAutomatedSecurityFixes(String owner, String repo) {
		HttpResponse<String> resp = delete(
				repoUrl(owner, repo) + PATH_AUTOMATED_SECURITY_FIXES
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " disabling automated-security-fixes on " + repo
			);
		}
	}

	public void enableImmutableReleases(String owner, String repo) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + PATH_IMMUTABLE_RELEASES
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling immutable-releases on " + repo
			);
		}
	}

	public void disableImmutableReleases(String owner, String repo) {
		HttpResponse<String> resp = delete(
				repoUrl(owner, repo) + PATH_IMMUTABLE_RELEASES
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " disabling immutable-releases on " + repo
			);
		}
	}

	public boolean getPrivateVulnerabilityReporting(String owner, String repo) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + PATH_PRIVATE_VULNERABILITY_REPORTING
		);
		if (resp.statusCode() == 200) {
			return readValue(
					resp.body(),
					PrivateVulnerabilityReportingResponse.class
			).enabled();
		}
		if (resp.statusCode() == 404) {
			return false;
		}
		throw new GitHubApiException(
				"HTTP " + resp.statusCode()
						+ " GET private-vulnerability-reporting on " + repo
		);
	}

	public void enablePrivateVulnerabilityReporting(String owner, String repo) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + PATH_PRIVATE_VULNERABILITY_REPORTING
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling private-vulnerability-reporting on "
							+ repo
			);
		}
	}

	public void disablePrivateVulnerabilityReporting(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = delete(
				repoUrl(owner, repo) + PATH_PRIVATE_VULNERABILITY_REPORTING
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " disabling private-vulnerability-reporting on "
							+ repo
			);
		}
	}

	public boolean getCodeScanningDefaultSetup(String owner, String repo) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + PATH_CODE_SCANNING_DEFAULT_SETUP
		);
		if (resp.statusCode() == 200) {
			return readValue(
					resp.body(),
					CodeScanningDefaultSetupResponse.class
			).isEnabled();
		}
		if (resp.statusCode() == 404) {
			return false;
		}
		throw new GitHubApiException(
				"HTTP " + resp.statusCode()
						+ " GET code-scanning/default-setup on " + repo
		);
	}

	public void enableCodeScanningDefaultSetup(String owner, String repo) {
		String body = writeValue(
				new CodeScanningDefaultSetupRequest(
						CodeScanningDefaultSetupResponse.State.CONFIGURED
				)
		);
		HttpResponse<String> resp = patch(
				repoUrl(owner, repo) + PATH_CODE_SCANNING_DEFAULT_SETUP,
				body
		);
		if (resp.statusCode() != 200 && resp.statusCode() != 202) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling code-scanning/default-setup on " + repo
			);
		}
	}

	public void disableCodeScanningDefaultSetup(String owner, String repo) {
		String body = writeValue(
				new CodeScanningDefaultSetupRequest(
						CodeScanningDefaultSetupResponse.State.NOT_CONFIGURED
				)
		);
		HttpResponse<String> resp = patch(
				repoUrl(owner, repo) + PATH_CODE_SCANNING_DEFAULT_SETUP,
				body
		);
		if (resp.statusCode() != 200 && resp.statusCode() != 202) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " disabling code-scanning/default-setup on "
							+ repo
			);
		}
	}

	public RulesetDetailsResponse createRuleset(
			String owner,
			String repo,
			RulesetRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = post(
				repoUrl(owner, repo) + "/rulesets",
				body
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating ruleset on "
							+ owner + "/" + repo + ": " + resp.body()
			);
		}
		return readValue(resp.body(), RulesetDetailsResponse.class);
	}

	public void deleteRuleset(String owner, String repo, long rulesetId) {
		HttpResponse<String> resp = delete(rulesetUrl(owner, repo, rulesetId));
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting ruleset "
							+ rulesetId + " on " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	public RulesetDetailsResponse updateRuleset(
			String owner,
			String repo,
			long rulesetId,
			RulesetRequest payload
	) {
		String body = writeValue(payload);
		HttpResponse<String> resp = put(
				rulesetUrl(owner, repo, rulesetId),
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating ruleset "
							+ rulesetId + " on " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
		return readValue(resp.body(), RulesetDetailsResponse.class);
	}

	// ─── Organization rulesets
	// ──────────────────────────────────────────────────────────

	public List<RulesetSummaryResponse> listOrgRulesets(String org) {
		HttpResponse<String> resp = get(orgUrl(org) + "/rulesets?per_page=100");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing rulesets for " + org
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(
						node -> mapper.convertValue(
								node,
								RulesetSummaryResponse.class
						)
				)
				.toList();
	}

	public RulesetDetailsResponse getOrgRuleset(String org, long rulesetId) {
		HttpResponse<String> resp = get(orgUrl(org) + "/rulesets/" + rulesetId);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET ruleset " + rulesetId
							+ " on " + org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), RulesetDetailsResponse.class);
	}

	public RulesetDetailsResponse createOrgRuleset(
			String org,
			RulesetRequest payload
	) {
		HttpResponse<String> resp = post(
				orgUrl(org) + "/rulesets",
				writeValue(payload)
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating ruleset on " + org
							+ ": " + resp.body()
			);
		}
		return readValue(resp.body(), RulesetDetailsResponse.class);
	}

	public RulesetDetailsResponse updateOrgRuleset(
			String org,
			long rulesetId,
			RulesetRequest payload
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/rulesets/" + rulesetId,
				writeValue(payload)
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating ruleset "
							+ rulesetId + " on " + org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), RulesetDetailsResponse.class);
	}

	public void deleteOrgRuleset(String org, long rulesetId) {
		HttpResponse<String> resp = delete(
				orgUrl(org) + "/rulesets/" + rulesetId
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting ruleset "
							+ rulesetId + " on " + org + ": " + resp.body()
			);
		}
	}

	public void replaceTopics(String owner, String repo, List<String> topics) {
		String body = writeValue(new ReplaceTopicsRequest(topics));
		HttpResponse<String> resp = put(repoUrl(owner, repo) + "/topics", body);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating topics for "
							+ owner + "/" + repo + ": " + resp.body()
			);
		}
	}

	public RepositoryDetailsResponse createUserRepository(
			RepositoryCreateRequest request
	) {
		String body = writeValue(request);
		HttpResponse<String> resp = post(baseUrl + "/user/repos", body);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating user repository: "
							+ resp.body()
			);
		}
		return readValue(resp.body(), RepositoryDetailsResponse.class);
	}

	public void deleteRepository(String owner, String repo) {
		HttpResponse<String> resp = delete(repoUrl(owner, repo));
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting " + owner + "/"
							+ repo + ": " + resp.body()
			);
		}
	}

	public SimpleUser getAuthenticatedUser() {
		HttpResponse<String> resp = get(baseUrl + "/user");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " getting authenticated user: " + resp.body()
			);
		}
		return readValue(resp.body(), SimpleUser.class);
	}

	// ─── Organizations
	// ─────────────────────────────────────────────────────

	public Optional<OrganizationResponse> getOrganization(String org) {
		HttpResponse<String> resp = get(orgUrl(org));
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " fetching organization "
							+ org + ": " + resp.body()
			);
		}
		return Optional.of(readValue(resp.body(), OrganizationResponse.class));
	}

	public void updateOrganization(
			String org,
			OrganizationUpdateRequest request
	) {
		HttpResponse<String> resp = patch(orgUrl(org), writeValue(request));
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating organization "
							+ org + ": " + resp.body()
			);
		}
	}

	public OrgActionsPermissionsResponse getOrgActionsPermissions(String org) {
		HttpResponse<String> resp = get(orgUrl(org) + "/actions/permissions");
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET actions permissions on "
							+ org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), OrgActionsPermissionsResponse.class);
	}

	public void updateOrgActionsPermissions(
			String org,
			OrgActionsPermissionsRequest request
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/permissions",
				writeValue(request)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating actions permissions on " + org + ": "
							+ resp.body()
			);
		}
	}

	public SelectedActions getOrgSelectedActions(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/permissions/selected-actions"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET selected actions on "
							+ org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), SelectedActions.class);
	}

	public void updateOrgSelectedActions(String org, SelectedActions selected) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/permissions/selected-actions",
				writeValue(selected)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating selected actions on " + org + ": "
							+ resp.body()
			);
		}
	}

	public WorkflowPermissions getOrgWorkflowPermissions(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/permissions/workflow"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " GET workflow permissions on " + org + ": "
							+ resp.body()
			);
		}
		return readValue(resp.body(), WorkflowPermissions.class);
	}

	public void updateOrgWorkflowPermissions(
			String org,
			WorkflowPermissions permissions
	) {
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/permissions/workflow",
				writeValue(permissions)
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating workflow permissions on " + org + ": "
							+ resp.body()
			);
		}
	}

	public List<OrgSecretResponse> getOrgActionSecrets(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/secrets?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for org action secrets on "
							+ org + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "secrets").stream()
				.map(s -> mapper.convertValue(s, OrgSecretResponse.class))
				.toList();
	}

	public OrgSecretResponse getOrgActionSecret(String org, String name) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/secrets/" + name
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET org action secret "
							+ name + " on " + org + ": " + resp.body()
			);
		}
		return readValue(resp.body(), OrgSecretResponse.class);
	}

	/** The repositories a {@code selected} secret is shared with. */
	public List<RepositorySummaryResponse> getOrgActionSecretRepositories(
			String org,
			String name
	) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/secrets/" + name
						+ "/repositories?per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for repositories of org "
							+ "secret " + name + " on " + org + ": "
							+ resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "repositories").stream()
				.map(
						node -> mapper.convertValue(
								node,
								RepositorySummaryResponse.class
						)
				)
				.toList();
	}

	public SecretPublicKeyResponse getOrgActionSecretPublicKey(String org) {
		HttpResponse<String> resp = get(
				orgUrl(org) + "/actions/secrets/public-key"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " GET org action secret public key on " + org
							+ ": " + resp.body()
			);
		}
		return readValue(resp.body(), SecretPublicKeyResponse.class);
	}

	public void createOrUpdateOrgActionSecret(
			String org,
			String name,
			String value,
			SecretVisibility visibility,
			List<Long> selectedRepositoryIds
	) {
		var publicKey = getOrgActionSecretPublicKey(org);
		var request = new OrgSecretRequest(
				Secrets.encryptSecret(publicKey.key(), value),
				publicKey.keyId(),
				visibility,
				visibility == SecretVisibility.SELECTED ? selectedRepositoryIds
						: null
		);
		HttpResponse<String> resp = put(
				orgUrl(org) + "/actions/secrets/" + name,
				writeValue(request)
		);
		if (resp.statusCode() != 201 && resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " PUT org action secret "
							+ name + " on " + org + ": " + resp.body()
			);
		}
	}

	// ─── Pagination
	// ──────────────────────────────────────────────────────────

	/**
	 * Collects all items from a paginated API response, following Link headers.
	 * The caller is responsible for validating the status of {@code firstResp}.
	 * {@code arrayField} names the JSON field that holds the array on each
	 * page; pass {@code null} when the page body is itself the array.
	 */
	private List<JsonNode> collectPaginatedArrayItems(
			HttpResponse<String> firstResp,
			String arrayField
	) {
		List<JsonNode> items = new ArrayList<>(
				arrayItems(firstResp, arrayField)
		);
		for (HttpResponse<String> page : remainingPages(
				firstResp,
				arrayField
		)) {
			items.addAll(arrayItems(page, arrayField));
		}
		return items;
	}

	/** Every item after the first page's, the pages fetched together. */
	private List<JsonNode> remainingItems(HttpResponse<String> firstResp) {
		List<JsonNode> items = new ArrayList<>();
		for (HttpResponse<String> page : remainingPages(firstResp, null)) {
			items.addAll(arrayItems(page, null));
		}
		return items;
	}

	private List<JsonNode> arrayItems(
			HttpResponse<String> resp,
			String arrayField
	) {
		JsonNode page = readTree(resp.body());
		Iterable<JsonNode> array = arrayField != null ? page.path(arrayField)
				: page;
		List<JsonNode> items = new ArrayList<>();
		for (JsonNode item : array) {
			items.add(item);
		}
		return items;
	}

	/**
	 * Every page after the first, in order.
	 * <p>
	 * A paginated GitHub response carries {@code rel="last"} as well as
	 * {@code rel="next"}, so the page count is known from the first answer and
	 * the rest can be asked for together. Walking {@code rel="next"} paid one
	 * round trip per page in series instead: the two pages of
	 * {@code /user/repos} for a 101-repository account were the first 1.31s of
	 * a check, before any repository had started.
	 * <p>
	 * Falls back to the walk when there is no {@code rel="last"} — GitHub omits
	 * it on the final page, and a cursor-paginated endpoint never sends one.
	 * Either way the listing is whatever the first response announced: a page
	 * added while it is being read is missed by both —
	 * {@link #withGrowthFallback} is what catches one added since.
	 */
	private List<HttpResponse<String>> remainingPages(
			HttpResponse<String> firstResp,
			String arrayField
	) {
		String last = extractLink(
				firstResp.headers().firstValue("Link").orElse(""),
				"last"
		);
		int lastPage = last == null ? 0 : pageNumber(last);
		List<HttpResponse<String>> pages = lastPage < 2
				? walkNextLinks(firstResp)
				: fetchPages(last, lastPage);
		return withGrowthFallback(firstResp, pages, arrayField);
	}

	private List<HttpResponse<String>> fetchPages(String last, int lastPage) {
		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<HttpResponse<String>>> pending = IntStream
					.rangeClosed(2, lastPage)
					.mapToObj(page -> pageUrl(last, page))
					.map(url -> executor.submit(() -> get(url)))
					.toList();
			return pending.stream().map(GitHubClient::await).map(resp -> {
				if (resp.statusCode() != 200) {
					throw new GitHubApiException(
							"HTTP " + resp.statusCode()
									+ " fetching next page: " + resp.body()
					);
				}
				return resp;
			}).toList();
		}
	}

	/**
	 * A cached page one can say the listing ends where it no longer does: a
	 * page that was exactly one page's worth of items carried no {@code Link}
	 * at all, and a cached {@code rel="last"} stops naming a page added past it
	 * — either way GitHub's 304 answers with no {@code Link} to say so (see
	 * {@link CachedHttpResponse}), so there is nothing in the response to
	 * notice the listing has grown. This does not depend on how GitHub computes
	 * a listing's ETag: whatever the final page assembled so far turns out to
	 * be, one that came back exactly full is probed for one page more, whether
	 * or not anything was ever cached. A genuinely final full page costs one
	 * wasted request; a grown one is the request that would otherwise never
	 * have been sent.
	 */
	private List<HttpResponse<String>> withGrowthFallback(
			HttpResponse<String> firstResp,
			List<HttpResponse<String>> pages,
			String arrayField
	) {
		HttpResponse<String> finalPage = pages.isEmpty() ? firstResp
				: pages.get(pages.size() - 1);
		int perPage = perPage(finalPage.request().uri().toString());
		if (perPage <= 0
				|| arrayItems(finalPage, arrayField).size() != perPage) {
			return pages;
		}
		HttpResponse<String> probe = get(forcedNextPageUrl(finalPage));
		if (probe.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + probe.statusCode() + " fetching next page: "
							+ probe.body()
			);
		}
		if (arrayItems(probe, arrayField).isEmpty()) {
			return pages;
		}
		List<HttpResponse<String>> grown = new ArrayList<>(pages);
		grown.add(probe);
		grown.addAll(walkNextLinks(probe));
		return grown;
	}

	/**
	 * The next page's URL, whether or not {@code page} just fetched carried an
	 * explicit {@code page} parameter of its own — page one's never does.
	 */
	private static String forcedNextPageUrl(HttpResponse<String> page) {
		String url = page.request().uri().toString();
		int current = pageNumber(url);
		return current == 0 ? url + "&page=2" : pageUrl(url, current + 1);
	}

	private List<HttpResponse<String>> walkNextLinks(
			HttpResponse<String> firstResp
	) {
		List<HttpResponse<String>> pages = new ArrayList<>();
		HttpResponse<String> resp = firstResp;
		String next;
		while ((next = extractLink(
				resp.headers().firstValue("Link").orElse(""),
				"next"
		)) != null) {
			resp = get(next);
			if (resp.statusCode() != 200) {
				throw new GitHubApiException(
						"HTTP " + resp.statusCode() + " fetching next page: "
								+ resp.body()
				);
			}
			pages.add(resp);
		}
		return pages;
	}

	/**
	 * Rethrows the page request's own exception rather than an
	 * {@link ExecutionException} wrapping it, so a failed page reads the same
	 * whether it was fetched in parallel or walked to. Everything a page
	 * request throws is a {@link GitHubApiException} already; anything else
	 * becomes one rather than reaching a caller that is only prepared for that.
	 */
	private static <T> T await(Future<T> pending) {
		try {
			return pending.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GitHubApiException("Interrupted fetching a page", e);
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

	private static String pageUrl(String url, int page) {
		return PAGE_PARAM.matcher(url)
				.replaceFirst(match -> match.group(1) + page);
	}

	private static int pageNumber(String url) {
		Matcher matcher = PAGE_PARAM.matcher(url);
		if (!matcher.find()) {
			return 0;
		}
		try {
			return Integer.parseInt(matcher.group(2));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int perPage(String url) {
		Matcher matcher = PER_PAGE_PARAM.matcher(url);
		if (!matcher.find()) {
			return 0;
		}
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private String repoUrl(String owner, String repo) {
		return baseUrl + "/repos/" + owner + "/" + repo;
	}

	private String orgUrl(String org) {
		return baseUrl + "/orgs/" + org;
	}

	private String pagesUrl(String owner, String repo) {
		return repoUrl(owner, repo) + "/pages";
	}

	private String branchProtectionUrl(
			String owner,
			String repo,
			String branch
	) {
		return repoUrl(owner, repo) + "/branches/" + branch + "/protection";
	}

	private String environmentUrl(String owner, String repo, String env) {
		return repoUrl(owner, repo) + "/environments/" + env;
	}

	private String rulesetUrl(String owner, String repo, long rulesetId) {
		return repoUrl(owner, repo) + "/rulesets/" + rulesetId;
	}

	private <T> T readValue(String json, Class<T> type) {
		try {
			return mapper.readValue(json, type);
		} catch (IOException e) {
			throw new GitHubApiException(
					"Failed to parse " + type.getSimpleName(),
					e
			);
		}
	}

	private String writeValue(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (IOException e) {
			throw new GitHubApiException("Failed to serialize request body", e);
		}
	}

	private JsonNode readTree(String json) {
		try {
			return mapper.readTree(json);
		} catch (IOException e) {
			throw new GitHubApiException("Failed to parse JSON", e);
		}
	}

	private HttpRequest.Builder requestBuilder(String url) {
		return HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2026-03-10");
	}

	/**
	 * Sends one request, waiting out and re-sending through a rate limit.
	 * <p>
	 * Two different things arrive as one here. A response that is otherwise
	 * fine but reports the budget spent is returned to the caller — it holds
	 * the data — after a pause that keeps the <em>next</em> request from being
	 * the one that is refused. A response that GitHub refused <em>because</em>
	 * of a limit carries no data, so it is re-sent after the pause: handing a
	 * secondary limit's 403 to the caller reported a group as unreadable that
	 * had never been read, and there is no field of the response that says
	 * which it was.
	 * <p>
	 * Either way the pause is handed to {@link RequestPacer} before this thread
	 * sleeps it out, because the limit is the token's and not this request's:
	 * every other thread has to stop too, or they spend the pause collecting
	 * refusals of their own.
	 */
	private HttpResponse<String> sendRequest(HttpRequest request) {
		try {
			for (int attempt = 1;; attempt++) {
				HttpResponse<String> resp = sendBounded(request);
				Duration pause = rateLimitPause(resp);
				if (pause == null) {
					return resp;
				}
				pacer.backOffFor(pause);
				report(pause, request);
				Thread.sleep(pause);
				if (!rateLimited(resp) || attempt == RATE_LIMIT_ATTEMPTS) {
					return resp;
				}
			}
		} catch (IOException e) {
			throw new GitHubApiException(
					request.method() + " " + request.uri() + " failed",
					e
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GitHubApiException(
					request.method() + " " + request.uri() + " interrupted",
					e
			);
		}
	}

	/**
	 * Sends one request, holding a permit for as long as it is in flight — see
	 * {@link #MAX_CONCURRENT_REQUESTS}. The permit is gone by the time
	 * {@code sendRequest} waits out a rate limit, so a thread parked until the
	 * reset is not holding a stream while it waits, and the pacer's own wait is
	 * taken before the permit for the same reason.
	 */
	private HttpResponse<String> sendBounded(HttpRequest request)
			throws IOException, InterruptedException {
		reportPacing(pacer.awaitTurn(RequestPacer.pointsFor(request)));
		inFlight.acquire();
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		} finally {
			inFlight.release();
		}
	}

	/**
	 * One GET, asked conditionally when drifty has seen the answer before.
	 * <p>
	 * GitHub does not charge a 304 against the primary rate limit — an
	 * unchanged account costs nothing of the 5000 an hour — and the request
	 * still carries the current token, so a 304 is proof that this token may
	 * read the resource. {@code max-age} is ignored on purpose: a body is only
	 * ever used to fill in a 304 GitHub has just sent.
	 */
	private HttpResponse<String> get(String url) {
		String key = cacheKey(url);
		ResponseCache.Entry cached = cache.lookup(key);
		HttpRequest.Builder builder = requestBuilder(url).GET();
		if (cached != null) {
			builder.header(HEADER_IF_NONE_MATCH, cached.etag());
		}
		HttpResponse<String> resp = sendRequest(builder.build());
		if (resp.statusCode() == 304 && cached != null) {
			cache.confirm(key);
			return new CachedHttpResponse(resp, cached);
		}
		if (resp.statusCode() == 200) {
			resp.headers()
					.firstValue("ETag")
					.ifPresent(
							etag -> cache.store(
									key,
									etag,
									resp.body(),
									resp.headers()
											.firstValue("Link")
											.orElse(null)
							)
					);
		}
		return resp;
	}

	/**
	 * What an entry is filed under: the URL without the host, so the file reads
	 * as {@code /repos/ArloL/drifty} and a WireMock port never reaches it.
	 */
	private String cacheKey(String url) {
		return url.startsWith(baseUrl) ? url.substring(baseUrl.length()) : url;
	}

	private HttpResponse<String> post(String url, String body) {
		return sendRequest(
				requestBuilder(url).header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.build()
		);
	}

	private HttpResponse<String> patch(String url, String body) {
		return sendRequest(
				requestBuilder(url).header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
						.method(
								"PATCH",
								HttpRequest.BodyPublishers.ofString(body)
						)
						.build()
		);
	}

	private HttpResponse<String> put(String url, String body) {
		return sendRequest(
				requestBuilder(url).header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
						.PUT(HttpRequest.BodyPublishers.ofString(body))
						.build()
		);
	}

	private HttpResponse<String> put(String url) {
		return sendRequest(
				requestBuilder(url).PUT(HttpRequest.BodyPublishers.noBody())
						.build()
		);
	}

	private HttpResponse<String> delete(String url) {
		return sendRequest(requestBuilder(url).DELETE().build());
	}

	/**
	 * Whether GitHub refused this request to protect a rate limit, rather than
	 * on its merits.
	 * <p>
	 * A secondary limit answers 403 or 429 with {@code Retry-After}; the
	 * primary one answers either status with {@code X-RateLimit-Remaining: 0}.
	 * A plain 403 — a token without a scope, an endpoint an organization does
	 * not have — carries neither, and has to keep reaching the caller as the
	 * failure it is, since that is what {@code FetchFailures} turns into the
	 * group's note.
	 */
	private static boolean rateLimited(HttpResponse<String> resp) {
		if (resp.statusCode() != 403 && resp.statusCode() != 429) {
			return false;
		}
		return resp.headers().firstValue("Retry-After").isPresent()
				|| budgetSpent(resp);
	}

	private static boolean budgetSpent(HttpResponse<String> resp) {
		return "0".equals(
				resp.headers()
						.firstValue("X-RateLimit-Remaining")
						.orElse("1000")
		);
	}

	/**
	 * How long to stay off the API after this response, or {@code null} when
	 * nothing in it says to wait.
	 * <p>
	 * {@code Retry-After} is all a secondary limit says and is a number of
	 * seconds from now; {@code X-RateLimit-Reset} is what the primary limit
	 * says and is an absolute epoch second. It is read only on a response that
	 * was refused, because GitHub also sends {@code Retry-After} on the 202 of
	 * a computation it has not finished, which is not a limit.
	 */
	private static Duration rateLimitPause(HttpResponse<String> resp) {
		boolean refused = rateLimited(resp);
		if (!refused && !budgetSpent(resp)) {
			return null;
		}
		Optional<String> retryAfter = refused
				? resp.headers().firstValue("Retry-After")
				: Optional.empty();
		if (retryAfter.isPresent()) {
			return Duration
					.ofSeconds(seconds(retryAfter.orElseThrow(), 60) + 1);
		}
		Duration untilReset = Duration.ofMillis(
				seconds(
						resp.headers()
								.firstValue("X-RateLimit-Reset")
								.orElse("0"),
						0
				) * 1000L - System.currentTimeMillis() + 1000L
		);
		if (untilReset.isPositive()) {
			return untilReset;
		}
		// Refused for a limit that named no window, or named one that has
		// already passed. Re-sending immediately is what earns the next one.
		return refused ? UNTIMED_RATE_LIMIT_PAUSE : null;
	}

	/**
	 * {@code Retry-After} may be an HTTP date rather than a count of seconds,
	 * and a header can be anything at all — neither is worth failing a run
	 * over, so an unreadable one takes the caller's fallback.
	 */
	private static long seconds(String header, long fallback) {
		try {
			return Long.parseLong(header.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Says once what every thread is about to wait for. Without it the run
	 * printed nothing at all: exhausting the budget parked a check for eight
	 * minutes in silence. With one line per thread it would have printed
	 * {@link #MAX_CONCURRENT_REQUESTS} copies of the same sentence.
	 */
	private void report(Duration pause, HttpRequest request) {
		long end = System.currentTimeMillis() + pause.toMillis();
		long reported = reportedPauseEnd.get();
		if (end - reported < PAUSE_REPORTED_WITHIN_MILLIS
				|| !reportedPauseEnd.compareAndSet(reported, end)) {
			return;
		}
		System.err.printf(
				"Rate limited on %s %s. Waiting %.1f seconds before continuing...%n",
				request.method(),
				request.uri().getPath(),
				pause.toMillis() / 1000.0
		);
	}

	/**
	 * Says once that the run is being slowed on purpose. Without it a check of
	 * an account past {@link RequestPacer#POINTS_PER_MINUTE} looks like a check
	 * that has hung — the same silence an exhausted budget used to park a run
	 * in, arrived at from the other direction.
	 */
	private void reportPacing(long waited) {
		if (waited < PACING_REPORTED_OVER_MILLIS
				|| !reportedPacing.compareAndSet(false, true)) {
			return;
		}
		System.err.printf(
				"Pacing requests to stay inside GitHub's secondary rate limit of %d points a minute. This check will take longer than the API alone would.%n",
				RequestPacer.POINTS_PER_MINUTE
		);
	}

	private static String extractLink(String linkHeader, String rel) {
		if (linkHeader == null || linkHeader.isBlank()) {
			return null;
		}
		for (String part : linkHeader.split(",")) {
			String[] segments = part.trim().split(";");
			if (segments.length == 2
					&& segments[1].trim().equals("rel=\"" + rel + "\"")) {
				return segments[0].trim().replaceAll("[<>]", "");
			}
		}
		return null;
	}

}
