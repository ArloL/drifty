package io.github.arlol.githubcheck.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

public class GitHubClient {

	// ─── Client
	// ──────────────────────────────────────────────────────────────

	private final String baseUrl;
	private final String token;
	private final HttpClient http;
	private final ObjectMapper mapper;

	public GitHubClient(String token) {
		this("https://api.github.com", token);
	}

	public GitHubClient(String baseUrl, String token) {
		this.baseUrl = baseUrl;
		this.token = token;
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

	public List<RepositoryMinimal> listOrgRepos(String org)
			throws IOException, InterruptedException {
		String url = baseUrl + "/orgs/" + org + "/repos?per_page=100&type=all";
		HttpResponse<String> resp = send(url);
		if (resp.statusCode() == 404) {
			// Not an org — personal account. /users/{name}/repos only
			// returns public repos; /user/repos returns everything
			// (public + private + archived) for the authenticated user.
			url = baseUrl + "/user/repos?per_page=100&type=owner";
			resp = send(url);
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + org
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(node -> mapper.convertValue(node, RepositoryMinimal.class))
				.toList();
	}

	public RepositoryFull getRepo(String org, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + org + "/" + repo
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " fetching repo " + org + "/"
							+ repo + ": " + resp.body()
			);
		}
		return mapper.readValue(resp.body(), RepositoryFull.class);
	}

	public boolean getVulnerabilityAlerts(String owner, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + owner + "/" + repo
						+ "/vulnerability-alerts"
		);
		if (resp.statusCode() == 204) {
			return true;
		}
		if (resp.statusCode() == 404) {
			return false;
		}
		throw new GitHubApiException(
				"HTTP " + resp.statusCode() + " GET vulnerability-alerts on "
						+ repo
		);
	}

	public boolean getAutomatedSecurityFixes(String org, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + org + "/" + repo
						+ "/automated-security-fixes"
		);
		if (resp.statusCode() == 200) {
			return mapper.readValue(resp.body(), AutomatedSecurityFixes.class)
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

	public Optional<ImmutableReleases> getImmutableReleases(
			String owner,
			String repo
	) throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + owner + "/" + repo + "/immutable-releases"
		);
		if (resp.statusCode() == 200) {
			return Optional
					.of(mapper.readValue(resp.body(), ImmutableReleases.class));
		}
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		throw new GitHubApiException(
				"HTTP " + resp.statusCode() + " GET immutable-releases on "
						+ repo
		);
	}

	public Optional<BranchProtectionResponse> getBranchProtection(
			String owner,
			String repo,
			String branch
	) throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + owner + "/" + repo + "/branches/" + branch
						+ "/protection"
		);
		if (resp.statusCode() == 404) {
			return Optional.empty();
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " GET branch protection on "
							+ repo
			);
		}
		return Optional.of(
				mapper.readValue(resp.body(), BranchProtectionResponse.class)
		);
	}

	public List<String> getActionSecretNames(String org, String repo)
			throws IOException, InterruptedException {
		String url = baseUrl + "/repos/" + org + "/" + repo
				+ "/actions/secrets?per_page=100";
		HttpResponse<String> resp = send(url);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for action secrets on "
							+ repo + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "secrets").stream()
				.map(s -> mapper.convertValue(s, Secret.class).name())
				.toList();
	}

	public List<EnvironmentDetailsResponse> getEnvironments(
			String org,
			String repo
	) throws IOException, InterruptedException {
		String url = baseUrl + "/repos/" + org + "/" + repo
				+ "/environments?per_page=100";
		HttpResponse<String> resp = send(url);
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

	public void updateEnvironment(
			String org,
			String repo,
			String envName,
			EnvironmentUpdateRequest payload
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(payload);
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + org + "/" + repo + "/environments/"
						+ envName,
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating environment "
							+ envName + " on " + org + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	public List<String> getEnvironmentSecretNames(
			String org,
			String repo,
			String env
	) throws IOException, InterruptedException {
		String url = baseUrl + "/repos/" + org + "/" + repo + "/environments/"
				+ env + "/secrets?per_page=100";
		HttpResponse<String> resp = send(url);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for environment secrets on "
							+ repo + "/" + env + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, "secrets").stream()
				.map(s -> mapper.convertValue(s, Secret.class).name())
				.toList();
	}

	public WorkflowPermissions getWorkflowPermissions(String org, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + org + "/" + repo
						+ "/actions/permissions/workflow"
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
		return mapper.readValue(resp.body(), WorkflowPermissions.class);
	}

	public void updateWorkflowPermissions(
			String owner,
			String repo,
			WorkflowPermissions permissions
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(
				Map.of(
						"default_workflow_permissions",
						permissions.defaultWorkflowPermissions()
								.name()
								.toLowerCase(Locale.ROOT),
						"can_approve_pull_request_reviews",
						permissions.canApprovePullRequestReviews()
				)
		);
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo
						+ "/actions/permissions/workflow",
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
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(payload);
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo + "/branches/" + branch
						+ "/protection",
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " updating branch protection on " + repo
			);
		}
		return mapper.readValue(resp.body(), BranchProtectionResponse.class);
	}

	public void updateRepository(
			String org,
			String repo,
			Map<String, Object> fields
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(fields);
		HttpResponse<String> resp = patch(
				baseUrl + "/repos/" + org + "/" + repo,
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating " + org + "/"
							+ repo + ": " + resp.body()
			);
		}
	}

	public Optional<PagesResponse> getPages(String owner, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + owner + "/" + repo + "/pages"
		);
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
		return Optional.of(mapper.readValue(resp.body(), PagesResponse.class));
	}

	public PagesResponse createPages(
			String owner,
			String repo,
			PagesCreateRequest payload
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(payload);
		HttpResponse<String> resp = post(
				baseUrl + "/repos/" + owner + "/" + repo + "/pages",
				body
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
		return mapper.readValue(resp.body(), PagesResponse.class);
	}

	public void updatePages(
			String owner,
			String repo,
			PagesUpdateRequest payload
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(payload);
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo + "/pages",
				body
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	public void deletePages(String owner, String repo)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest
				.newBuilder(
						URI.create(
								baseUrl + "/repos/" + owner + "/" + repo
										+ "/pages"
						)
				)
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2026-03-10")
				.DELETE()
				.build();
		HttpResponse<String> resp = http
				.send(request, HttpResponse.BodyHandlers.ofString());
		handleRateLimit(resp);
		if (resp.statusCode() != 204 && resp.statusCode() != 404) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	public void enableVulnerabilityAlerts(String owner, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo
						+ "/vulnerability-alerts",
				""
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling vulnerability-alerts on " + repo
			);
		}
	}

	public void enableAutomatedSecurityFixes(String owner, String repo)
			throws IOException, InterruptedException {
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo
						+ "/automated-security-fixes",
				""
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling automated-security-fixes on " + repo
			);
		}
	}

	public List<RulesetSummaryResponse> listRulesets(String owner, String repo)
			throws IOException, InterruptedException {
		String url = baseUrl + "/repos/" + owner + "/" + repo
				+ "/rulesets?per_page=100";
		HttpResponse<String> resp = send(url);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing rulesets for "
							+ owner + "/" + repo + ": " + resp.body()
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

	public RulesetDetailsResponse createRuleset(
			String owner,
			String repo,
			RulesetRequest payload
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(payload);
		HttpResponse<String> resp = post(
				baseUrl + "/repos/" + owner + "/" + repo + "/rulesets",
				body
		);
		if (resp.statusCode() != 201) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " creating ruleset on "
							+ owner + "/" + repo + ": " + resp.body()
			);
		}
		return mapper.readValue(resp.body(), RulesetDetailsResponse.class);
	}

	public RulesetDetailsResponse updateRuleset(
			String owner,
			String repo,
			long rulesetId,
			RulesetRequest payload
	) throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(payload);
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo + "/rulesets/"
						+ rulesetId,
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating ruleset "
							+ rulesetId + " on " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
		return mapper.readValue(resp.body(), RulesetDetailsResponse.class);
	}

	public void replaceTopics(String owner, String repo, List<String> topics)
			throws IOException, InterruptedException {
		String body = mapper.writeValueAsString(Map.of("names", topics));
		HttpResponse<String> resp = put(
				baseUrl + "/repos/" + owner + "/" + repo + "/topics",
				body
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating topics for "
							+ owner + "/" + repo + ": " + resp.body()
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
	) throws IOException, InterruptedException {
		List<JsonNode> items = new ArrayList<>();
		HttpResponse<String> resp = firstResp;
		while (true) {
			JsonNode page = mapper.readTree(resp.body());
			Iterable<JsonNode> array = arrayField != null
					? page.path(arrayField)
					: page;
			for (JsonNode item : array) {
				items.add(item);
			}
			String next = extractNextLink(
					resp.headers().firstValue("Link").orElse("")
			);
			if (next == null) {
				break;
			}
			resp = send(next);
			if (resp.statusCode() != 200) {
				throw new GitHubApiException(
						"HTTP " + resp.statusCode() + " fetching next page: "
								+ resp.body()
				);
			}
		}
		return items;
	}

	private HttpResponse<String> post(String url, String body)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("Content-Type", "application/json")
				.header("X-GitHub-Api-Version", "2026-03-10")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> resp = http
				.send(request, HttpResponse.BodyHandlers.ofString());
		handleRateLimit(resp);
		return resp;
	}

	private HttpResponse<String> patch(String url, String body)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("Content-Type", "application/json")
				.header("X-GitHub-Api-Version", "2026-03-10")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> resp = http
				.send(request, HttpResponse.BodyHandlers.ofString());
		handleRateLimit(resp);
		return resp;
	}

	private HttpResponse<String> put(String url, String body)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("Content-Type", "application/json")
				.header("X-GitHub-Api-Version", "2026-03-10")
				.PUT(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> resp = http
				.send(request, HttpResponse.BodyHandlers.ofString());
		handleRateLimit(resp);
		return resp;
	}

	private HttpResponse<String> send(String url)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", "2026-03-10")
				.GET()
				.build();
		HttpResponse<String> resp = http
				.send(request, HttpResponse.BodyHandlers.ofString());
		handleRateLimit(resp);
		return resp;
	}

	private void handleRateLimit(HttpResponse<String> resp)
			throws InterruptedException {
		String remaining = resp.headers()
				.firstValue("X-RateLimit-Remaining")
				.orElse("1000");
		if ("0".equals(remaining)) {
			long resetEpoch = Long.parseLong(
					resp.headers().firstValue("X-RateLimit-Reset").orElse("0")
			);
			long sleepMs = (resetEpoch * 1000L) - System.currentTimeMillis()
					+ 1000L;
			if (sleepMs > 0) {
				System.err.printf(
						"Rate limit reached. Sleeping %.1f seconds until reset...%n",
						sleepMs / 1000.0
				);
				Thread.sleep(sleepMs);
			}
		}
	}

	private static String extractNextLink(String linkHeader) {
		if (linkHeader == null || linkHeader.isBlank()) {
			return null;
		}
		for (String part : linkHeader.split(",")) {
			String[] segments = part.trim().split(";");
			if (segments.length == 2
					&& segments[1].trim().equals("rel=\"next\"")) {
				return segments[0].trim().replaceAll("[<>]", "");
			}
		}
		return null;
	}

	public RulesetDetailsResponse getRuleset(
			String owner,
			String repo,
			long rulesetId
	) throws IOException, InterruptedException {
		HttpResponse<String> resp = send(
				baseUrl + "/repos/" + owner + "/" + repo + "/rulesets/"
						+ rulesetId
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
		return mapper.readValue(resp.body(), RulesetDetailsResponse.class);
	}

}
