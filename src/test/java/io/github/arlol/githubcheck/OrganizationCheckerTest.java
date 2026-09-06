package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * Each test stubs only the endpoints the group it manages is allowed to read. A
 * group whose request escaped its guard in {@code fetchState} would hit an
 * unstubbed path and fail these tests, which is what makes them a guard test as
 * well as a checker test.
 */
@WireMockTest
class OrganizationCheckerTest {

	private OrganizationChecker checker;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		checker = new OrganizationChecker(
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				false,
				Map.of(),
				new DriftyState()
		);
	}

	private static Drifty.Organization onlySettings() {
		return Desired.organization()
				.withManaged(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_SETTINGS)
						)
				);
	}

	private static void stubOrg(String description) {
		stubFor(get(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("""
				{
				  "login": "my-org",
				  "description": %s,
				  "default_repository_permission": "read",
				  "default_repository_branch": "main",
				  "has_organization_projects": true,
				  "has_repository_projects": true,
				  "members_can_create_repositories": true,
				  "members_can_create_public_repositories": true,
				  "members_can_create_private_repositories": true,
				  "members_can_create_pages": true,
				  "members_can_create_public_pages": true,
				  "members_can_create_private_pages": true,
				  "members_can_delete_repositories": true,
				  "members_can_change_repo_visibility": true,
				  "members_can_invite_outside_collaborators": true,
				  "members_can_create_teams": true,
				  "members_can_view_dependency_insights": true
				}
				""".formatted(description))));
	}

	@Test
	void matchingSettingsReportOk() {
		stubOrg("null");

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.OK);
		assertThat(entry.unmanaged()).containsExactlyInAnyOrder(
				"org_actions_permissions",
				"org_workflow_permissions",
				"org_action_secrets",
				"org_action_variables",
				"org_webhooks",
				"org_custom_properties",
				"org_rulesets",
				"org_code_security_configurations",
				"org_teams",
				"org_members",
				"org_runner_groups"
		);
	}

	@Test
	void driftedDescriptionIsReported() {
		stubOrg("\"stale\"");

		CheckResult.Entry entry = checker.check(
				"my-org",
				onlySettings().withDescription("wanted"),
				List.of()
		);

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.diffs()).singleElement()
				.asString()
				.startsWith("org_settings.description:");
		assertThat(entry.fixPreview()).containsExactly("org_settings");
	}

	/**
	 * The repositories of the private secret are never asked for: that request
	 * is not stubbed, so a fetch that sent it would fail here.
	 */
	@Test
	void secretRepositoriesAreReadOnlyForSelectedSecrets() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets")).willReturn(
						okJson(
								"""
										{
										  "total_count": 2,
										  "secrets": [
										    {"name": "PAT", "updated_at": "t1", "visibility": "private"},
										    {"name": "SHARED", "updated_at": "t2", "visibility": "selected"}
										  ]
										}
										"""
						)
				)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/secrets/SHARED/repositories"
						)
				).willReturn(okJson("""
						{
						  "total_count": 1,
						  "repositories": [
						    {"id": 1, "name": "one", "archived": false}
						  ]
						}
						"""))
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_ACTION_SECRETS)
						)
				)
		);

		assertThat(state.actionSecrets()).containsExactly(
				new ActualOrgSecret(
						"PAT",
						"t1",
						SecretVisibility.PRIVATE,
						List.of()
				),
				new ActualOrgSecret(
						"SHARED",
						"t2",
						SecretVisibility.SELECTED,
						List.of("one")
				)
		);
	}

	/**
	 * {@code Would fix:} is what an operator reads to decide whether to run
	 * {@code --fix}, so it must name the groups that drifted and no others.
	 * Three of the four groups here match GitHub exactly, and all four return a
	 * fix object whether or not they found drift — keying the preview on that
	 * object rather than on its items previewed
	 * {@code org_settings, org_actions_permissions, org_workflow_permissions}
	 * for this organization.
	 */
	@Test
	void fixPreviewNamesOnlyTheGroupThatDrifted() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions"))
						.willReturn(okJson("""
								{
								  "enabled_repositories": "all",
								  "allowed_actions": "all"
								}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions/workflow"))
						.willReturn(okJson("""
								{
								  "default_workflow_permissions": "read",
								  "can_approve_pull_request_reviews": true
								}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets"))
						.willReturn(okJson("{\"secrets\": []}"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/variables"))
						.willReturn(okJson("{\"variables\": []}"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/hooks"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/properties/schema"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/code-security/configurations"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/runner-groups"))
						.willReturn(okJson("{\"runner_groups\": []}"))
		);

		CheckResult.Entry entry = checker
				.check("my-org", Desired.organization(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.DRIFT);
		assertThat(entry.diffs()).singleElement()
				.asString()
				.startsWith(
						"org_workflow_permissions.default_workflow_permissions:"
				);
		assertThat(entry.fixPreview())
				.containsExactly("org_workflow_permissions");
	}

	@Test
	void unknownOrganizationIsMissing() {
		stubFor(
				get(urlPathEqualTo("/orgs/my-org"))
						.willReturn(aResponse().withStatus(404))
		);

		CheckResult.Entry entry = checker
				.check("my-org", onlySettings(), List.of());

		assertThat(entry.status()).isEqualTo(CheckResult.Status.MISSING);
	}

	/**
	 * Variables are read only when their group is managed, and the repositories
	 * of a variable only under {@code selected} visibility.
	 */
	@Test
	void variablesAreReadOnlyWhenManaged() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/variables"))
						.willReturn(
								okJson(
										"""
												{
												  "total_count": 2,
												  "variables": [
												    {"name": "REGION", "value": "eu", "visibility": "all"},
												    {"name": "SHARED", "value": "x", "visibility": "selected"}
												  ]
												}
												"""
								)
						)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/variables/SHARED/repositories"
						)
				).willReturn(
						okJson(
								"""
										{"total_count": 1, "repositories": [{"id": 1, "name": "one", "archived": false}]}
										"""
						)
				)
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(
										Drifty.OrgGroupName.ORG_ACTION_VARIABLES
								)
						)
				)
		);

		assertThat(state.actionVariables()).containsExactly(
				new ActualOrgVariable(
						"REGION",
						"eu",
						SecretVisibility.ALL,
						List.of()
				),
				new ActualOrgVariable(
						"SHARED",
						"x",
						SecretVisibility.SELECTED,
						List.of("one")
				)
		);

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.actionVariables()).isEmpty();
		verify(
				1,
				getRequestedFor(
						urlPathEqualTo("/orgs/my-org/actions/variables")
				)
		);
	}

	/**
	 * Webhooks are read only when their group is managed; the listing is what
	 * an organization someone else administers answers with a 403.
	 */
	@Test
	void webhooksAreReadOnlyWhenManaged() {
		stubOrg("null");
		stubFor(get(urlPathEqualTo("/orgs/my-org/hooks")).willReturn(okJson("""
				[
				  {
				    "id": 7,
				    "name": "web",
				    "active": true,
				    "events": ["push", "pull_request"],
				    "config": {
				      "url": "https://example.com/hook",
				      "content_type": "json",
				      "insecure_ssl": "0",
				      "secret": "********"
				    },
				    "updated_at": "2024-01-01T00:00:00Z"
				  }
				]
				""")));

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_WEBHOOKS)
						)
				)
		);

		assertThat(state.webhooks()).containsExactly(
				new ActualWebhook(
						7,
						"https://example.com/hook",
						"json",
						false,
						true,
						Set.of("push", "pull_request"),
						true,
						"2024-01-01T00:00:00Z"
				)
		);

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.webhooks()).isEmpty();
		verify(1, getRequestedFor(urlPathEqualTo("/orgs/my-org/hooks")));
	}

	/**
	 * Definitions are read only when their group is managed, and the
	 * enterprise-owned ones are dropped: the organization cannot change them.
	 */
	@Test
	void customPropertiesAreReadOnlyWhenManagedAndEnterpriseOnesDropped() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/properties/schema"))
						.willReturn(
								okJson(
										"""
												[
												  {"property_name": "tier", "source_type": "organization", "value_type": "single_select",
												   "required": true, "default_value": "gold", "description": null,
												   "allowed_values": ["gold", "silver"], "values_editable_by": "org_actors"},
												  {"property_name": "owner", "source_type": "enterprise", "value_type": "string",
												   "required": false, "default_value": null, "values_editable_by": "org_actors"}
												]
												"""
								)
						)
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(
										Drifty.OrgGroupName.ORG_CUSTOM_PROPERTIES
								)
						)
				)
		);

		assertThat(state.customProperties()).containsExactly(
				new ActualCustomProperty(
						"tier",
						"single_select",
						true,
						"gold",
						List.of(),
						"",
						List.of("gold", "silver"),
						"org_actors"
				)
		);

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.customProperties()).isEmpty();
		verify(
				1,
				getRequestedFor(
						urlPathEqualTo("/orgs/my-org/properties/schema")
				)
		);
	}

	/**
	 * Rulesets are read only when their group is managed, one detail request
	 * per listed ruleset, and enterprise rulesets are dropped without one.
	 */
	@Test
	void rulesetsAreReadOnlyWhenManagedAndEnterpriseOnesDropped() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "main", "source_type": "Organization", "enforcement": "active"},
										  {"id": 2, "name": "corp", "source_type": "Enterprise", "enforcement": "active"}
										]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets/1")).willReturn(
						okJson(
								"""
										{"id": 1, "name": "main", "target": "branch", "enforcement": "active",
										 "source_type": "Organization",
										 "conditions": {"ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []},
										                "repository_name": {"include": ["~ALL"], "exclude": [], "protected": true}},
										 "rules": [{"type": "non_fast_forward"}]}
										"""
						)
				)
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(Drifty.OrgGroupName.ORG_RULESETS)
						)
				)
		);

		assertThat(state.rulesets()).singleElement().satisfies(ruleset -> {
			assertThat(ruleset.name()).isEqualTo("main");
			assertThat(ruleset.noForcePushes()).isTrue();
			assertThat(ruleset.repositoryNameInclude()).containsExactly("~ALL");
			assertThat(ruleset.repositoryNameProtected()).isTrue();
		});
		verify(0, getRequestedFor(urlPathEqualTo("/orgs/my-org/rulesets/2")));

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.rulesets()).isEmpty();
		verify(1, getRequestedFor(urlPathEqualTo("/orgs/my-org/rulesets")));
	}

	/**
	 * Configurations are read only when their group is managed; GitHub's global
	 * ones are dropped, the defaults listing is read once, and the attached
	 * repositories once per organization configuration.
	 */
	@Test
	void codeSecurityConfigurationsAreReadOnlyWhenManaged() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/code-security/configurations"))
						.willReturn(
								okJson(
										"""
												[
												  {"id": 1, "target_type": "global", "name": "GitHub recommended"},
												  {"id": 2, "target_type": "organization", "name": "baseline",
												   "description": null, "secret_scanning": "enabled", "enforcement": "enforced"}
												]
												"""
								)
						)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/code-security/configurations/defaults"
						)
				).willReturn(
						okJson(
								"""
										[{"default_for_new_repos": "all", "configuration": {"id": 2, "name": "baseline"}}]
										"""
						)
				)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/code-security/configurations/2/repositories"
						)
				).willReturn(
						okJson(
								"""
										[
										  {"status": "attached", "repository": {"id": 10, "name": "one"}},
										  {"status": "detached", "repository": {"id": 11, "name": "two"}}
										]
										"""
						)
				)
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(
										Drifty.OrgGroupName.ORG_CODE_SECURITY_CONFIGURATIONS
								)
						)
				)
		);

		assertThat(state.codeSecurityConfigurations()).singleElement()
				.satisfies(configuration -> {
					assertThat(configuration.id()).isEqualTo(2);
					assertThat(configuration.description()).isEmpty();
					assertThat(configuration.settings())
							.containsEntry("secret_scanning", "enabled")
							.containsEntry("dependency_graph", "not_set");
					assertThat(configuration.defaultForNewRepos())
							.isEqualTo("all");
					assertThat(configuration.repositories())
							.containsExactly("one");
				});
		verify(
				0,
				getRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/code-security/configurations/1/repositories"
						)
				)
		);

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.codeSecurityConfigurations()).isEmpty();
		verify(
				1,
				getRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/code-security/configurations"
						)
				)
		);
	}

	/**
	 * Teams cost one listing plus two member listings each; members two
	 * listings, one per role. Neither is sent unless its group is managed.
	 */
	@Test
	void teamsAndMembersAreReadOnlyWhenManaged() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "Core", "slug": "core", "description": null, "privacy": "closed",
										   "notification_setting": "notifications_enabled", "parent": null},
										  {"id": 2, "name": "Corp", "slug": "corp", "privacy": "closed",
										   "notification_setting": "notifications_enabled", "type": "enterprise"}
										]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams/core/members"))
						.withQueryParam("role", equalTo("member"))
						.willReturn(okJson("[{\"login\": \"alice\"}]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams/core/members"))
						.withQueryParam("role", equalTo("maintainer"))
						.willReturn(okJson("[{\"login\": \"bob\"}]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.withQueryParam("role", equalTo("admin"))
						.willReturn(okJson("[{\"login\": \"bob\"}]"))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.withQueryParam("role", equalTo("member"))
						.willReturn(okJson("[{\"login\": \"alice\"}]"))
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(
										Drifty.OrgGroupName.ORG_TEAMS,
										Drifty.OrgGroupName.ORG_MEMBERS
								)
						)
				)
		);

		assertThat(state.teams()).containsExactly(
				new ActualTeam(
						1,
						"core",
						"Core",
						"",
						"closed",
						"notifications_enabled",
						null,
						Set.of("alice"),
						Set.of("bob")
				)
		);
		assertThat(state.members()).containsExactly(
				new ActualOrgMember("bob", "admin"),
				new ActualOrgMember("alice", "member")
		);
		verify(
				0,
				getRequestedFor(
						urlPathEqualTo("/orgs/my-org/teams/corp/members")
				)
		);

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.teams()).isEmpty();
		assertThat(settingsOnly.members()).isEmpty();
		verify(1, getRequestedFor(urlPathEqualTo("/orgs/my-org/teams")));
		verify(2, getRequestedFor(urlPathEqualTo("/orgs/my-org/members")));
	}

	/**
	 * A runner group's repositories cost one request each and only exist under
	 * {@code selected}; the Actions policy's repository selection the same way.
	 * Neither listing is sent unless its group is managed.
	 */
	@Test
	void runnerGroupsAndActionsRepositoriesAreReadOnlyWhenSelected() {
		stubOrg("null");
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/runner-groups"))
						.willReturn(
								okJson(
										"""
												{"total_count": 2, "runner_groups": [
												  {"id": 1, "name": "Default", "visibility": "all", "default": true,
												   "allows_public_repositories": true, "restricted_to_workflows": false, "selected_workflows": []},
												  {"id": 2, "name": "gpu", "visibility": "selected", "default": false,
												   "allows_public_repositories": false, "restricted_to_workflows": true,
												   "selected_workflows": ["my-org/one/.github/workflows/ci.yml@refs/heads/main"]}
												]}
												"""
								)
						)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/runner-groups/2/repositories"
						)
				).willReturn(
						okJson(
								"""
										{"total_count": 1, "repositories": [{"id": 10, "name": "one", "archived": false}]}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions"))
						.willReturn(
								okJson(
										"""
												{"enabled_repositories": "selected", "allowed_actions": "all"}
												"""
								)
						)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/repositories"
						)
				).willReturn(
						okJson(
								"""
										{"total_count": 1, "repositories": [{"id": 10, "name": "one", "archived": false}]}
										"""
						)
				)
		);

		OrganizationState state = checker.fetchState(
				"my-org",
				ManagedGroups.of(
						new Drifty.OrgManaged(
								Drifty.ManageMode.ONLY,
								List.of(
										Drifty.OrgGroupName.ORG_RUNNER_GROUPS,
										Drifty.OrgGroupName.ORG_ACTIONS_PERMISSIONS
								)
						)
				)
		);

		assertThat(state.runnerGroups()).containsExactly(
				new ActualRunnerGroup(
						1,
						"Default",
						"all",
						true,
						true,
						false,
						Set.of(),
						List.of()
				),
				new ActualRunnerGroup(
						2,
						"gpu",
						"selected",
						false,
						false,
						true,
						Set.of(
								"my-org/one/.github/workflows/ci.yml@refs/heads/main"
						),
						List.of("one")
				)
		);
		assertThat(state.actionsPermissions().selectedRepositories())
				.containsExactly("one");
		verify(
				0,
				getRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/actions/runner-groups/1/repositories"
						)
				)
		);

		OrganizationState settingsOnly = checker
				.fetchState("my-org", ManagedGroups.of(onlySettings().managed));
		assertThat(settingsOnly.runnerGroups()).isEmpty();
		verify(
				1,
				getRequestedFor(
						urlPathEqualTo("/orgs/my-org/actions/runner-groups")
				)
		);
	}

}
