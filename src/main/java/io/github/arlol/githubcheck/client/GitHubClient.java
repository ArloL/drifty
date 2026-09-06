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

	private static final String PATH_VULNERABILITY_ALERTS = "/vulnerability-alerts";
	private static final String PATH_AUTOMATED_SECURITY_FIXES = "/automated-security-fixes";
	private static final String PATH_IMMUTABLE_RELEASES = "/immutable-releases";
	private static final String PATH_PRIVATE_VULNERABILITY_REPORTING = "/private-vulnerability-reporting";
	private static final String PATH_CODE_SCANNING_DEFAULT_SETUP = "/code-scanning/default-setup";

	private static final String HEADER_CONTENT_TYPE = "Content-Type";
	private static final String MEDIA_TYPE_JSON = "application/json";

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
		HttpResponse<String> resp = get(
				baseUrl + "/user/repos?per_page=100&type=owner"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " listing repos for " + login
							+ ": " + resp.body()
			);
		}
		return summaries(resp);
	}

	private List<RepositorySummaryResponse> summaries(
			HttpResponse<String> resp
	) {
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
				repoUrl(owner, repo) + PATH_VULNERABILITY_ALERTS
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

	public Optional<BranchProtectionResponse> getBranchProtection(
			String owner,
			String repo,
			String branch
	) {
		HttpResponse<String> resp = get(
				branchProtectionUrl(owner, repo, branch)
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

	// ─── Collaborators, teams and members
	// ──────────────────────────────────────────────────────────

	/** Direct collaborators only: {@code affiliation=direct}. */
	public List<CollaboratorResponse> getCollaborators(
			String owner,
			String repo
	) {
		HttpResponse<String> resp = get(
				repoUrl(owner, repo)
						+ "/collaborators?affiliation=direct&per_page=100"
		);
		if (resp.statusCode() != 200) {
			throw new GitHubApiException(
					"HTTP " + resp.statusCode() + " for collaborators of "
							+ owner + "/" + repo + ": " + resp.body()
			);
		}
		return collectPaginatedArrayItems(resp, null).stream()
				.map(c -> mapper.convertValue(c, CollaboratorResponse.class))
				.toList();
	}

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

	public TeamResponse createTeam(String org, TeamRequest team) {
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
		HttpResponse<String> resp = get(rulesetUrl(owner, repo, rulesetId));
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
