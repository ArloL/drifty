package io.github.arlol.githubcheck.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import net.jcip.annotations.Immutable;

@Immutable
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

	public List<RepositorySummaryResponse> listOrgRepos(String owner) {
		String url = baseUrl + "/orgs/" + owner
				+ "/repos?per_page=100&type=all";
		HttpResponse<String> resp = get(url);
		if (resp.statusCode() == 404) {
			// Not an org — personal account. /users/{name}/repos only
			// returns public repos; /user/repos returns everything
			// (public + private + archived) for the authenticated user.
			url = baseUrl + "/user/repos?per_page=100&type=owner";
			resp = get(url);
		}
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + owner
							+ ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
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

	public boolean getVulnerabilityAlerts(String owner, String repo) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/vulnerability-alerts"
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

	public boolean getAutomatedSecurityFixes(String owner, String repo) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/automated-security-fixes"
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

	public Optional<ImmutableReleasesResponse> getImmutableReleases(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/immutable-releases"
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

	public Optional<BranchProtectionResponse> getBranchProtection(
			String owner,
			String repo,
			String branch
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/branches/" + branch + "/protection"
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
		return Optional
				.of(readValue(resp.body(), BranchProtectionResponse.class));
	}

	public List<String> getActionSecretNames(String owner, String repo) {
		String url = repoUrl(owner, repo) + "/actions/secrets?per_page=100";
		HttpResponse<String> resp = get(url);
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
				repoUrl(owner, repo) + "/environments/" + envName,
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
				repoUrl(owner, repo) + "/environments/" + envName,
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
				repoUrl(owner, repo) + "/environments/" + envName
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting environment "
							+ envName + " on " + owner + "/" + repo + ": "
							+ resp.body()
			);
		}
	}

	public List<String> getEnvironmentSecretNames(
			String owner,
			String repo,
			String env
	) {
		String url = repoUrl(owner, repo) + "/environments/" + env
				+ "/secrets?per_page=100";
		HttpResponse<String> resp = get(url);
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
				repoUrl(owner, repo) + "/environments/" + env
						+ "/secrets/public-key"
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
				repoUrl(owner, repo) + "/environments/" + env + "/secrets/"
						+ name,
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
				repoUrl(owner, repo) + "/branches/" + branch + "/protection",
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
				repoUrl(owner, repo) + "/branches/" + branch + "/protection"
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
		HttpResponse<String> resp = get(repoUrl(owner, repo) + "/pages");
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
		HttpResponse<String> resp = post(repoUrl(owner, repo) + "/pages", body);
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
		HttpResponse<String> resp = put(repoUrl(owner, repo) + "/pages", body);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " updating pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	public void deletePages(String owner, String repo) {
		HttpResponse<String> resp = delete(repoUrl(owner, repo) + "/pages");
		if (resp.statusCode() != 204 && resp.statusCode() != 404) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " deleting pages for " + owner
							+ "/" + repo + ": " + resp.body()
			);
		}
	}

	public void enableVulnerabilityAlerts(String owner, String repo) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + "/vulnerability-alerts"
		);
		if (resp.statusCode() != 204) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode()
							+ " enabling vulnerability-alerts on " + repo
			);
		}
	}

	public void enableAutomatedSecurityFixes(String owner, String repo) {
		HttpResponse<String> resp = put(
				repoUrl(owner, repo) + "/automated-security-fixes"
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
				repoUrl(owner, repo) + "/vulnerability-alerts"
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
				repoUrl(owner, repo) + "/automated-security-fixes"
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
				repoUrl(owner, repo) + "/immutable-releases"
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
				repoUrl(owner, repo) + "/immutable-releases"
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
				repoUrl(owner, repo) + "/private-vulnerability-reporting"
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
				repoUrl(owner, repo) + "/private-vulnerability-reporting"
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
				repoUrl(owner, repo) + "/private-vulnerability-reporting"
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
				repoUrl(owner, repo) + "/code-scanning/default-setup"
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
				repoUrl(owner, repo) + "/code-scanning/default-setup",
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
				repoUrl(owner, repo) + "/code-scanning/default-setup",
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

	public List<RulesetSummaryResponse> listRulesets(
			String owner,
			String repo
	) {
		String url = repoUrl(owner, repo) + "/rulesets?per_page=100";
		HttpResponse<String> resp = get(url);
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
		HttpResponse<String> resp = delete(
				repoUrl(owner, repo) + "/rulesets/" + rulesetId
		);
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
				repoUrl(owner, repo) + "/rulesets/" + rulesetId,
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
		List<JsonNode> items = new ArrayList<>();
		HttpResponse<String> resp = firstResp;
		while (true) {
			JsonNode page = readTree(resp.body());
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
			resp = get(next);
			if (resp.statusCode() != 200) {
				throw new GitHubApiException(
						"HTTP " + resp.statusCode() + " fetching next page: "
								+ resp.body()
				);
			}
		}
		return items;
	}

	private String repoUrl(String owner, String repo) {
		return baseUrl + "/repos/" + owner + "/" + repo;
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

	private HttpResponse<String> sendRequest(HttpRequest request) {
		try {
			HttpResponse<String> resp = http
					.send(request, HttpResponse.BodyHandlers.ofString());
			handleRateLimit(resp);
			return resp;
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

	private HttpResponse<String> get(String url) {
		return sendRequest(requestBuilder(url).GET().build());
	}

	private HttpResponse<String> post(String url, String body) {
		return sendRequest(
				requestBuilder(url).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body))
						.build()
		);
	}

	private HttpResponse<String> patch(String url, String body) {
		return sendRequest(
				requestBuilder(url).header("Content-Type", "application/json")
						.method(
								"PATCH",
								HttpRequest.BodyPublishers.ofString(body)
						)
						.build()
		);
	}

	private HttpResponse<String> put(String url, String body) {
		return sendRequest(
				requestBuilder(url).header("Content-Type", "application/json")
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

	private void handleRateLimit(HttpResponse<String> resp) {
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
				try {
					Thread.sleep(sleepMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new GitHubApiException(
							"Interrupted while waiting for rate limit reset",
							e
					);
				}
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
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo) + "/rulesets/" + rulesetId
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
		return readValue(resp.body(), RulesetDetailsResponse.class);
	}

}
