package io.github.arlol.githubcheck;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.GraphQlStub;
import io.github.arlol.githubcheck.testsupport.PklFormat;

/**
 * Exports a state and loads the exported file straight back through
 * {@link PklConfigLoader} and {@link GitHubCheck#check}, against the same
 * WireMock state the export was taken from: the property this protects is that
 * {@code --export}'s output is a config an unmodified {@code drifty} accepts as
 * already-satisfied, not merely a file that parses.
 * <p>
 * A file that reports drift against the state it came from would tell a
 * {@code --fix} run to change something back to a value the organization never
 * chose — every exporter's own unit tests check what one field renders as, but
 * only loading the assembled file back through the real checker can catch a
 * field the assembler dropped, mis-keyed, or paired with the wrong schema
 * default. The fixture below is deliberately not at GitHub's defaults: an
 * organization at its defaults exports an empty file and would pass this test
 * whether or not the exporter worked at all.
 * <p>
 * Two ways a section can be in the fixture and still prove nothing, both of
 * which have happened. A stub answering an empty listing exports nothing, and a
 * stub answering something drifty cannot parse fails the group, which the
 * export then writes out as a {@code managed} exclusion the check skips —
 * silently, and indistinguishably from a section that round-tripped. The
 * assertion that no group was left unmanaged is what catches the second;
 * against the first there is only reading the fixture.
 * <p>
 * The one thing here that cannot round-trip is a secret. GitHub never returns a
 * secret's value, so the state file has no baseline for one on a freshly
 * exported config and the first run reports {@code SecretMissingBaseline} — for
 * a hand-written config just the same. The three secret listings are therefore
 * the only ones deliberately left empty.
 */
@WireMockTest
class ExportRoundTripTest {

	private static final String SCHEMA = Path.of("config/drifty.pkl")
			.toAbsolutePath()
			.toString();

	@Test
	void anExportedFileReportsNoDriftAgainstTheStateItCameFrom(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganization();
		stubRepository();
		stubFor(GraphQlStub.answering(REPOSITORY_GRAPHQL));

		GitHubClient client = new GitHubClient(
				wm.getHttpBaseUrl(),
				"test-token"
		);
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("my-org"), out, SCHEMA);
		assertThat(exitCode).isZero();

		PklFormat.assertFormatted(out);

		DriftyConfig config = PklConfigLoader.load(out);
		// A group whose read failed is exported as a `managed` exclusion, and
		// the check then skips it — so a stub answering something drifty
		// cannot parse makes its whole section pass this test by not being
		// there. Stating that nothing was excluded is what keeps every stub
		// below honest: a Pages payload missing one primitive field slipped
		// through exactly this way.
		Drifty.Organization org = config.organizations().get("my-org");
		assertThat(org.managed.groups).isEmpty();
		assertThat(org.repositories).allSatisfy(
				repository -> assertThat(repository.managed.groups).isEmpty()
		);
		var orgChecker = new OrganizationChecker(
				client,
				false,
				Map.of(),
				new DriftyState()
		);
		var repoChecker = new RepositoryChecker(client, false);

		CheckResult result = GitHubCheck
				.check(config, client, orgChecker, repoChecker);

		// hasDrift() alone would only say "something is wrong" — naming the
		// diffs is what turns a failure here into "fix this exporter field"
		// instead of an hour spent re-deriving which one from scratch.
		assertThat(problems(result)).isEmpty();
		assertThat(result.hasDrift()).isFalse();
	}

	/**
	 * An export of an unchanged account produces the identical file every time.
	 * That is what lets an adopter commit the file and read a later diff as
	 * "something on GitHub changed" rather than "drifty ran again", and nothing
	 * tested it.
	 * <p>
	 * What actually provides it, established by reversing each in turn: the
	 * {@code sorted()} in {@code AccountExporter.addUnmanagedGroups} orders the
	 * {@code managed} block, and {@code addFailureNote} emits each note where
	 * the exporter puts that group's section, so note order is the exporter's
	 * fixed order rather than arrival order. CLAUDE.md credited
	 * {@code FetchFailures.Collecting}'s own {@code sorted()} for this; that
	 * one is redundant — every consumer sorts again — and removing it leaves
	 * this test green. Its {@code synchronized} is the half that is
	 * load-bearing, because one repository's groups now fail on different
	 * threads.
	 * <p>
	 * Two of the three failures are in the same repository on purpose: there is
	 * one {@code Collecting} per entity, so a fixture with one failure each
	 * would sort trivially and pass whatever the ordering rule did.
	 * <p>
	 * Three runs rather than two. Thread scheduling is what varies, and one
	 * repeat is weak evidence about a race; three is not proof either, but the
	 * ordering assertion below is the part that does not depend on luck.
	 * Everything but the first line is compared: that line carries
	 * {@code Instant.now()}, which is the one thing that has to differ.
	 */
	@Test
	void anExportIsByteIdenticalEveryTimeItRuns(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganization();
		stubRepository();
		stubFor(GraphQlStub.atDefaults());
		// Three failures, and two of them in the same repository on purpose:
		// one Collecting per entity, so a single failure sorts trivially and
		// would prove nothing about the ordering rule.
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/secrets"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/hooks"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);

		var bodies = new ArrayList<String>();
		for (int run = 0; run < 3; run++) {
			Path out = dir.resolve("export-" + run + ".pkl");
			// A fresh client each time, so every run is cold: the question is
			// whether thread order reaches the file, not whether the response
			// cache does.
			int exitCode = ExportRunner.run(
					new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
					List.of("my-org"),
					out,
					SCHEMA
			);
			assertThat(exitCode).isZero();
			String file = Files.readString(out);
			bodies.add(file.substring(file.indexOf('\n') + 1));
		}

		assertThat(bodies).as(
				"three cold exports of the same state, differing only in the"
						+ " timestamp line this strips"
		).containsOnly(bodies.getFirst());
		// Not vacuous: the two same-entity failures have to be in the file,
		// in the order the comparator puts them, or there is nothing for the
		// sort to have got right.
		assertThat(bodies.getFirst()).contains("// org_teams:");
		assertThat(bodies.getFirst()).containsSubsequence(
				"\"action_secrets\"",
				"\"webhooks\"",
				"// action_secrets:",
				"// webhooks:"
		);
	}

	/**
	 * Issue #136: a group the export could not read has to come back as an
	 * unmanaged group, not only as a {@code //} note.
	 * <p>
	 * The note says which request failed and why, but no later run can act on a
	 * comment — so an export whose token could not read one group produced a
	 * file whose very first {@code drifty} run died on exactly the request the
	 * export had already given up on. Both 403s below stay stubbed for the
	 * check that follows: the run passes because {@code managed} keeps the
	 * requests from being sent at all, not because the endpoints recovered.
	 * <p>
	 * One organization group and one repository group, since {@code managed}
	 * and {@code OrgManaged} are separate fields on separate types and an
	 * export that filled in only one of them would still leave half its file
	 * uncheckable.
	 */
	@Test
	void aGroupTheExportCouldNotReadIsLeftUnmanagedSoTheFileStillChecks(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubOrganization();
		stubRepository();
		stubFor(GraphQlStub.atDefaults());
		// Registered last, so WireMock resolves these two paths to the 403
		// rather than to the success stubs above.
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/secrets"))
						.willReturn(aResponse().withStatus(403).withBody("""
								{"message": "Forbidden"}
								"""))
		);

		GitHubClient client = new GitHubClient(
				wm.getHttpBaseUrl(),
				"test-token"
		);
		Path out = dir.resolve("export.pkl");

		int exitCode = ExportRunner.run(client, List.of("my-org"), out, SCHEMA);
		assertThat(exitCode).isZero();

		PklFormat.assertFormatted(out);

		DriftyConfig config = PklConfigLoader.load(out);
		assertThat(config.organizations().get("my-org").managed.groups)
				.containsExactly(Drifty.OrgGroupName.ORG_TEAMS);
		assertThat(
				config.organizations().get("my-org").repositories.stream()
						.filter(r -> "widget".equals(r.name))
						.findFirst()
						.orElseThrow().managed.groups
		).containsExactly(Drifty.GroupName.ACTION_SECRETS);

		CheckResult result = GitHubCheck.check(
				config,
				client,
				new OrganizationChecker(
						client,
						false,
						Map.of(),
						new DriftyState()
				),
				new RepositoryChecker(client, false)
		);

		assertThat(problems(result)).isEmpty();
		assertThat(result.hasDrift()).isFalse();
	}

	/**
	 * Every diff, plus an ERROR's message and a MISSING entry's name, since
	 * neither of those carry a diff of its own but both mean the round trip
	 * failed just as much as a DRIFT would.
	 */
	private static List<String> problems(CheckResult result) {
		List<String> problems = new ArrayList<>();
		for (CheckResult.Entry entry : result.orgs()) {
			problems.addAll(problemsFor(entry));
		}
		for (CheckResult.Entry entry : result.repos()) {
			problems.addAll(problemsFor(entry));
		}
		return problems;
	}

	private static List<String> problemsFor(CheckResult.Entry entry) {
		var problems = new ArrayList<String>();
		problems.addAll(entry.diffs());
		if (entry.status() == CheckResult.Status.ERROR) {
			problems.add(entry.name() + ": ERROR: " + entry.error());
		}
		if (entry.status() == CheckResult.Status.MISSING) {
			problems.add(entry.name() + ": MISSING");
		}
		// UNKNOWN is a repository GitHub has that the config never names —
		// exactly what happens to one the export could not fetch details
		// for: it becomes a note in the listing rather than an entry, so the
		// round trip's own config omits it too. That is real information
		// this test exists to catch, not something to wave through the way
		// skipping this status did.
		if (entry.status() == CheckResult.Status.UNKNOWN) {
			problems.add(entry.name() + ": UNKNOWN");
		}
		return problems;
	}

	/**
	 * The same property for a personal account, which reaches none of the code
	 * above: its repositories come from {@code /user/repos} rather than an
	 * organization listing, and the file nests them under {@code users} instead
	 * of {@code organizations}. Two repository reads are skipped as well —
	 * {@code /teams} and {@code /properties/values} answer 404 on a user-owned
	 * repository whatever the token can do — so a config naming either would be
	 * one drifty could not satisfy.
	 * <p>
	 * One repository off its defaults in one field is enough: everything
	 * between the listing and the file is the same code the organization test
	 * above already drives.
	 */
	@Test
	void aPersonalAccountsExportReportsNoDriftEither(
			WireMockRuntimeInfo wm,
			@TempDir Path dir
	) throws Exception {
		stubFor(
				get(urlPathEqualTo("/orgs/solo"))
						.willReturn(aResponse().withStatus(404))
		);
		stubFor(get(urlPathEqualTo("/user")).willReturn(okJson("""
				{"login": "solo", "id": 1, "type": "User", "site_admin": false}
				""")));
		stubFor(
				get(urlPathEqualTo("/user/repos")).willReturn(
						okJson(
								"""
										[{"id": 1, "name": "sketch", "archived": false, "visibility": "public"}]
										"""
						)
				)
		);
		stubPersonalRepository();
		stubFor(GraphQlStub.atDefaults());

		GitHubClient client = new GitHubClient(
				wm.getHttpBaseUrl(),
				"test-token"
		);
		Path out = dir.resolve("export.pkl");

		assertThat(ExportRunner.run(client, List.of("solo"), out, SCHEMA))
				.isZero();

		PklFormat.assertFormatted(out);

		DriftyConfig config = PklConfigLoader.load(out);
		assertThat(config.users()).containsOnlyKeys("solo");
		assertThat(config.users().get("solo").repositories).allSatisfy(
				repository -> assertThat(repository.managed.groups).isEmpty()
		);

		CheckResult result = GitHubCheck.check(
				config,
				client,
				new OrganizationChecker(
						client,
						false,
						Map.of(),
						new DriftyState()
				),
				new RepositoryChecker(client, false)
		);

		assertThat(problems(result)).isEmpty();
		assertThat(result.hasDrift()).isFalse();
	}

	// ─── Stubs ────────────────────────────────────────────────────────────

	/**
	 * The one query {@code fetchState} sends, answered with a repository
	 * ruleset, a branch protection and a direct collaborator. These three
	 * groups are reachable only through GraphQL — the REST reads they replaced
	 * are gone — so a fixture that stubs the endpoints and leaves this at
	 * {@code atDefaults()} exercises none of them.
	 */
	private static final String REPOSITORY_GRAPHQL = GraphQlStub.sections(
			"""
					{
					  "databaseId": 42, "name": "main-rules",
					  "target": "BRANCH", "enforcement": "ACTIVE",
					  "source": {"__typename": "Repository"},
					  "conditions": {"refName": {"include": ["~DEFAULT_BRANCH"], "exclude": []}},
					  "bypassActors": {"nodes": []},
					  "rules": {"nodes": [
					    {"type": "REQUIRED_LINEAR_HISTORY", "parameters": null},
					    {"type": "NON_FAST_FORWARD", "parameters": null}
					  ]}
					}
					""",
			"""
					{
					  "pattern": "release/*",
					  "matchingRefs": {"nodes": []},
					  "isAdminEnforced": true, "requiresLinearHistory": true,
					  "requiresStatusChecks": false, "requiresApprovingReviews": false,
					  "restrictsPushes": false, "allowsForcePushes": false,
					  "requiresConversationResolution": true
					}
					""",
			"""
					{"permission": "MAINTAIN", "node": {"login": "carol"}}
					""",
			true
	);

	/**
	 * An organization with a non-default {@code description}, a check-only
	 * setting drifty can report but never write
	 * ({@code twoFactorRequirementEnabled}), one team, one ruleset with a
	 * {@code pullRequest} rule, and one webhook. Every other field matches
	 * {@code config/drifty.pkl}'s defaults, so only the fields named above are
	 * expected to appear in the exported file.
	 */
	/**
	 * One organization-owned configuration, off GitHub's defaults in every
	 * field {@code config/drifty.pkl} declares, plus the two sub-option objects
	 * the schema leaves nullable. A configuration set to the schema's defaults
	 * would round-trip as an empty body and prove nothing about the seventeen
	 * toggles.
	 */
	private static final String CODE_SECURITY_CONFIGURATIONS = """
			[
			  {
			    "id": 3,
			    "target_type": "organization",
			    "name": "baseline",
			    "description": "The one every repository gets",
			    "advanced_security": "enabled",
			    "dependency_graph": "enabled",
			    "dependency_graph_autosubmit_action": "enabled",
			    "dependency_graph_autosubmit_action_options": {"labeled_runners": true},
			    "dependabot_alerts": "enabled",
			    "dependabot_security_updates": "enabled",
			    "dependabot_delegated_alert_dismissal": "enabled",
			    "code_scanning_default_setup": "enabled",
			    "code_scanning_default_setup_options": {
			      "runner_type": "labeled", "runner_label": "code-scanning"
			    },
			    "code_scanning_options": {"allow_advanced": true},
			    "code_scanning_delegated_alert_dismissal": "enabled",
			    "secret_scanning": "enabled",
			    "secret_scanning_push_protection": "enabled",
			    "secret_scanning_delegated_bypass": "enabled",
			    "secret_scanning_delegated_bypass_options": {
			      "reviewers": [{"security_configuration_id": 3, "reviewer_id": 1, "reviewer_type": "TEAM"}]
			    },
			    "secret_scanning_validity_checks": "enabled",
			    "secret_scanning_non_provider_patterns": "enabled",
			    "secret_scanning_generic_secrets": "enabled",
			    "secret_scanning_delegated_alert_dismissal": "enabled",
			    "private_vulnerability_reporting": "enabled",
			    "enforcement": "unenforced"
			  }
			]
			""";

	private static void stubOrganization() {
		stubFor(get(urlPathEqualTo("/orgs/my-org")).willReturn(okJson("""
				{
				  "login": "my-org",
				  "description": "Widgets, Inc.",
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
				  "members_can_view_dependency_insights": true,
				  "two_factor_requirement_enabled": true
				}
				""")));
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions"))
						.willReturn(okJson("""
								{
								  "enabled_repositories": "selected",
								  "allowed_actions": "selected"
								}
								"""))
		);
		// Both "selected" modes, each of which hangs a second listing off the
		// permissions read — the allow-list and the repository selection.
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/selected-actions"
						)
				).willReturn(okJson("""
						{
						  "github_owned_allowed": false,
						  "verified_allowed": true,
						  "patterns_allowed": ["my-org/*"]
						}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/repositories"
						)
				).willReturn(
						okJson(
								"""
										{"repositories": [{"id": 1, "name": "widget", "archived": false}]}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/permissions/workflow"))
						.willReturn(okJson("""
								{
								  "default_workflow_permissions": "write",
								  "can_approve_pull_request_reviews": true
								}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/variables"))
						.willReturn(
								okJson(
										"""
												{"variables": [
												  {"name": "REGION", "value": "eu-central-1", "visibility": "all"}
												]}
												"""
								)
						)
		);
		stubFor(get(urlPathEqualTo("/orgs/my-org/hooks")).willReturn(okJson("""
				[
				  {
				    "id": 7,
				    "name": "web",
				    "active": true,
				    "events": ["pull_request"],
				    "config": {
				      "url": "https://example.com/hook",
				      "content_type": "json",
				      "insecure_ssl": "0"
				    },
				    "updated_at": "2024-01-01T00:00:00Z"
				  }
				]
				""")));
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/properties/schema"))
						.willReturn(
								okJson(
										"""
												[
												  {"property_name": "owning-team", "source_type": "organization",
												   "value_type": "single_select", "required": true,
												   "default_value": "platform", "description": "Who is paged",
												   "allowed_values": ["platform", "payments"],
												   "values_editable_by": "org_actors"}
												]
												"""
								)
						)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "protect-main", "source_type": "Organization", "enforcement": "active"}
										]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/rulesets/1")).willReturn(
						okJson(
								"""
										{
										  "id": 1,
										  "name": "protect-main",
										  "target": "branch",
										  "enforcement": "active",
										  "source_type": "Organization",
										  "conditions": {
										    "ref_name": {"include": ["~DEFAULT_BRANCH"], "exclude": []}
										  },
										  "rules": [
										    {"type": "pull_request", "parameters": {"required_approving_review_count": 1}},
										    {"type": "required_status_checks", "parameters": {
										      "strict_required_status_checks_policy": true,
										      "required_status_checks": [{"context": "build", "integration_id": 15368}]
										    }},
										    {"type": "workflows", "parameters": {"workflows": [
										      {"path": ".github/workflows/ci.yml", "repository_id": 1, "ref": "refs/heads/main"}
										    ]}},
										    {"type": "merge_queue", "parameters": {
										      "check_response_timeout_minutes": 60,
										      "grouping_strategy": "ALLGREEN",
										      "max_entries_to_build": 5,
										      "max_entries_to_merge": 5,
										      "merge_method": "SQUASH",
										      "min_entries_to_merge": 1,
										      "min_entries_to_merge_wait_minutes": 5
										    }}
										  ],
										  "bypass_actors": [
										    {"actor_id": null, "actor_type": "OrganizationAdmin", "bypass_mode": "always"},
										    {"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "pull_request"},
										    {"actor_id": 7, "actor_type": "Team", "bypass_mode": "always"},
										    {"actor_id": 9, "actor_type": "Integration", "bypass_mode": "exempt"},
										    {"actor_id": 11, "actor_type": "User", "bypass_mode": "always"},
										    {"actor_id": null, "actor_type": "DeployKey", "bypass_mode": "always"}
										  ]
										}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/code-security/configurations"))
						.willReturn(okJson(CODE_SECURITY_CONFIGURATIONS))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/code-security/configurations/defaults"
						)
				).willReturn(
						okJson(
								"""
										[{"default_for_new_repos": "public", "configuration": {"id": 3}}]
										"""
						)
				)
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/orgs/my-org/code-security/configurations/3/repositories"
						)
				).willReturn(
						okJson(
								"""
										[{"status": "attached", "repository": {"id": 1, "name": "widget"}}]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "platform", "slug": "platform", "description": null, "privacy": "closed",
										   "notification_setting": "notifications_enabled", "parent": null}
										]
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams/platform/members"))
						.withQueryParam("role", equalTo("member"))
						.willReturn(okJson("""
								[{"login": "bob", "id": 2}]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/teams/platform/members"))
						.withQueryParam("role", equalTo("maintainer"))
						.willReturn(okJson("""
								[{"login": "alice", "id": 1}]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.withQueryParam("role", equalTo("admin"))
						.willReturn(okJson("""
								[{"login": "alice", "id": 1}]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/members"))
						.withQueryParam("role", equalTo("member"))
						.willReturn(okJson("""
								[{"login": "bob", "id": 2}]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/actions/runner-groups"))
						.willReturn(
								okJson(
										"""
												{"runner_groups": [
												  {"id": 2, "name": "gpu", "visibility": "all", "default": false,
												   "inherited": false, "allows_public_repositories": false,
												   "restricted_to_workflows": false, "selected_workflows": []}
												]}
												"""
								)
						)
		);
		stubFor(
				get(urlPathEqualTo("/orgs/my-org/repos")).willReturn(
						okJson(
								"""
										[
										  {"id": 1, "name": "widget", "archived": false, "visibility": "public"},
										  {"id": 2, "name": "legacy", "archived": true, "visibility": "private"}
										]
										"""
						)
				)
		);
	}

	/**
	 * One repository with a non-default merge setting
	 * ({@code deleteBranchOnMerge}), a description long enough that the
	 * exported assignment wraps, and one environment with a non-default wait
	 * timer. Every other field matches {@code config/drifty.pkl}'s repository
	 * defaults.
	 * <p>
	 * The description is that long on purpose: {@code pkl format} moves the
	 * value of an assignment past a hundred columns onto its own line, so
	 * {@link io.github.arlol.githubcheck.export.PklWriter} does too, and only
	 * evaluating the file again proves the wrapped form is still the same
	 * string Pkl reads back (issue #138).
	 */
	private static void stubRepository() {
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget")).willReturn(
						okJson(
								"""
										{
										  "id": 1,
										  "name": "widget",
										  "owner": {"login": "my-org", "type": "Organization"},
										  "description": "A GitHub Actions action that creates a new version using a CalVer-style derivative and pushes it",
										  "private": false,
										  "fork": false,
										  "archived": false,
										  "disabled": false,
										  "is_template": false,
										  "visibility": "public",
										  "default_branch": "main",
										  "has_issues": true,
										  "has_projects": true,
										  "has_wiki": true,
										  "has_discussions": false,
										  "has_pages": true,
										  "allow_forking": true,
										  "web_commit_signoff_required": false,
										  "allow_squash_merge": true,
										  "allow_merge_commit": true,
										  "allow_rebase_merge": true,
										  "allow_auto_merge": false,
										  "delete_branch_on_merge": true,
										  "allow_update_branch": false,
										  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
										  "squash_merge_commit_message": "COMMIT_MESSAGES",
										  "merge_commit_title": "MERGE_MESSAGE",
										  "merge_commit_message": "PR_TITLE",
										  "security_and_analysis": {
										    "secret_scanning": {"status": "enabled"},
										    "secret_scanning_push_protection": {"status": "enabled"},
										    "secret_scanning_non_provider_patterns": {"status": "disabled"},
										    "secret_scanning_validity_checks": {"status": "disabled"}
										  }
										}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(204))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/automated-security-fixes"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/immutable-releases"))
						.willReturn(okJson("""
								{"enabled": false}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/private-vulnerability-reporting"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/code-scanning/default-setup"
						)
				).willReturn(okJson("""
						{"state": "not-configured"}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/branches"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/actions/variables"))
						.willReturn(
								okJson(
										"""
												{"variables": [{"name": "STAGE", "value": "production"}]}
												"""
								)
						)
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/environments"))
						.willReturn(okJson("""
								{
								  "environments": [
								    {
								      "name": "prod",
								      "protection_rules": [
								        {"type": "wait_timer", "wait_timer": 10}
								      ]
								    }
								  ]
								}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/environments/prod/secrets"
						)
				).willReturn(okJson("""
						{"secrets": []}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/environments/prod/variables"
						)
				).willReturn(okJson("""
						{"variables": []}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/hooks"))
						.willReturn(okJson("""
								[
								  {
								    "id": 11,
								    "name": "web",
								    "active": false,
								    "events": ["push", "release"],
								    "config": {
								      "url": "https://example.com/repo-hook",
								      "content_type": "form",
								      "insecure_ssl": "1"
								    },
								    "updated_at": "2024-01-01T00:00:00Z"
								  }
								]
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/collaborators"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/teams")).willReturn(
						okJson(
								"""
										[{"slug": "platform", "permission": "push", "access_source": "direct",
										  "permissions": {"pull": true, "triage": true, "push": true,
										                  "maintain": false, "admin": false}}]
										"""
						)
				)
		);
		stubFor(
				get(
						urlPathEqualTo("/repos/my-org/widget/properties/values")
				).willReturn(okJson("""
						[{"property_name": "owning-team", "value": "platform"}]
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/my-org/widget/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "write",
						  "can_approve_pull_request_reviews": true
						}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/rulesets"))
						.willReturn(okJson("[]"))
		);
		// An archived repository, whose export reads its details and nothing
		// else: RepositoryExporter writes settings it never compares, and the
		// account listing omits the merge fields, so ARCHIVED_ONLY still costs
		// this one request.
		stubFor(
				get(urlPathEqualTo("/repos/my-org/legacy")).willReturn(
						okJson(
								"""
										{
										  "id": 2,
										  "name": "legacy",
										  "owner": {"login": "my-org", "type": "Organization"},
										  "description": "Retired, kept for its history",
										  "private": true,
										  "fork": false,
										  "archived": true,
										  "disabled": false,
										  "is_template": false,
										  "visibility": "private",
										  "default_branch": "master",
										  "has_issues": false,
										  "has_projects": false,
										  "has_wiki": false,
										  "has_discussions": false,
										  "has_pages": false,
										  "allow_forking": true,
										  "web_commit_signoff_required": false,
										  "allow_squash_merge": true,
										  "allow_merge_commit": true,
										  "allow_rebase_merge": true,
										  "allow_auto_merge": false,
										  "delete_branch_on_merge": false,
										  "allow_update_branch": false,
										  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
										  "squash_merge_commit_message": "COMMIT_MESSAGES",
										  "merge_commit_title": "MERGE_MESSAGE",
										  "merge_commit_message": "PR_TITLE"
										}
										"""
						)
				)
		);
		stubFor(
				get(urlPathEqualTo("/repos/my-org/widget/pages")).willReturn(
						okJson(
								"""
										{
										  "url": "https://api.github.com/repos/my-org/widget/pages",
										  "status": "built",
										  "custom_404": false,
										  "build_type": "legacy",
										  "source": {"branch": "gh-pages", "path": "/docs"},
										  "public": true,
										  "https_enforced": true
										}
										"""
						)
				)
		);
	}

	/**
	 * One user-owned repository, at GitHub's defaults except
	 * {@code allowAutoMerge}. {@code /teams} and {@code /properties/values} are
	 * deliberately unstubbed: {@code fetchState} must not send them for a
	 * repository no organization owns, and a stub here would hide it doing so.
	 */
	private static void stubPersonalRepository() {
		stubFor(get(urlPathEqualTo("/repos/solo/sketch")).willReturn(okJson("""
				{
				  "id": 1,
				  "name": "sketch",
				  "owner": {"login": "solo", "type": "User"},
				  "private": false,
				  "fork": false,
				  "archived": false,
				  "disabled": false,
				  "is_template": false,
				  "visibility": "public",
				  "default_branch": "main",
				  "has_issues": true,
				  "has_projects": true,
				  "has_wiki": true,
				  "has_discussions": false,
				  "has_pages": false,
				  "allow_forking": true,
				  "web_commit_signoff_required": false,
				  "allow_squash_merge": true,
				  "allow_merge_commit": true,
				  "allow_rebase_merge": true,
				  "allow_auto_merge": true,
				  "delete_branch_on_merge": false,
				  "allow_update_branch": false,
				  "squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
				  "squash_merge_commit_message": "COMMIT_MESSAGES",
				  "merge_commit_title": "MERGE_MESSAGE",
				  "merge_commit_message": "PR_TITLE"
				}
				""")));
		stubFor(
				get(urlPathEqualTo("/repos/solo/sketch/vulnerability-alerts"))
						.willReturn(aResponse().withStatus(204))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/solo/sketch/automated-security-fixes"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/solo/sketch/immutable-releases"))
						.willReturn(okJson("""
								{"enabled": false}
								"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/solo/sketch/private-vulnerability-reporting"
						)
				).willReturn(okJson("""
						{"enabled": false}
						"""))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/solo/sketch/code-scanning/default-setup"
						)
				).willReturn(okJson("""
						{"state": "not-configured"}
						"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/solo/sketch/actions/secrets"))
						.willReturn(okJson("""
								{"secrets": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/solo/sketch/actions/variables"))
						.willReturn(okJson("""
								{"variables": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/solo/sketch/environments"))
						.willReturn(okJson("""
								{"environments": []}
								"""))
		);
		stubFor(
				get(urlPathEqualTo("/repos/solo/sketch/hooks"))
						.willReturn(okJson("[]"))
		);
		stubFor(
				get(
						urlPathEqualTo(
								"/repos/solo/sketch/actions/permissions/workflow"
						)
				).willReturn(okJson("""
						{
						  "default_workflow_permissions": "write",
						  "can_approve_pull_request_reviews": true
						}
						"""))
		);
	}

}
