package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;
import io.github.arlol.githubcheck.drift.DriftItem;

class OrgCheckerDiffTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	private static final String GOOD_DETAILS_JSON = """
			{
				"description": "",
				"homepage": "",
				"has_issues": true,
				"has_projects": true,
				"has_wiki": true,
				"has_discussions": false,
				"is_template": false,
				"allow_forking": true,
				"web_commit_signoff_required": false,
				"default_branch": "main",
				"topics": [],
				"allow_merge_commit": true,
				"allow_squash_merge": true,
				"allow_rebase_merge": true,
				"allow_update_branch": false,
				"allow_auto_merge": false,
				"delete_branch_on_merge": false,
				"squash_merge_commit_title": "COMMIT_OR_PR_TITLE",
				"squash_merge_commit_message": "COMMIT_MESSAGES",
				"merge_commit_title": "MERGE_MESSAGE",
				"merge_commit_message": "PR_TITLE",
				"visibility": "public",
				"archived": false,
				"security_and_analysis": {
					"secret_scanning": {"status": "enabled"},
					"secret_scanning_push_protection": {"status": "enabled"}
				}
			}
			""";

	private static final String GOOD_BRANCH_PROTECTION_JSON = """
			{
				"enforce_admins": {"enabled": true},
				"required_linear_history": {"enabled": true},
				"allow_force_pushes": {"enabled": false},
				"required_status_checks": {
					"strict": false,
					"checks": []
				},
				"required_pull_request_reviews": {
					"dismiss_stale_reviews": true,
					"require_code_owner_reviews": false,
					"required_approving_review_count": null
				}
			}
			""";

	private static final String GOOD_WORKFLOW_PERMISSIONS_JSON = """
			{
				"default_workflow_permissions": "write",
				"can_approve_pull_request_reviews": true
			}
			""";

	private OrgChecker checker;

	@BeforeEach
	void setUp() {
		checker = new OrgChecker((GitHubClient) null, false);
	}

	private Map<DriftGroup, List<DriftFix>> computeGroupDrifts(
			RepositoryState actual,
			Drifty.Repository desired
	) {
		return checker.computeGroupDrifts(actual, desired);
	}

	// ─── Helpers
	// ──────────────────────────────────────────────────────────

	private static <T> T parse(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static ObjectNode merge(String baseJson, String overridesJson) {
		try {
			ObjectNode base = (ObjectNode) MAPPER.readTree(baseJson);
			ObjectNode overrides = (ObjectNode) MAPPER.readTree(overridesJson);
			base.setAll(overrides);
			return base;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static RepositoryState goodPublicState() {
		return new StateBuilder().build();
	}

	private static Drifty.Repository defaultDesired() {
		return Desired.withBranchProtection(
				Desired.repository("owner", "repo"),
				"main",
				Desired.branchProtection()
						.withEnforceAdmins(true)
						.withRequiredLinearHistory(true)
						.withDismissStaleReviews(true)
		);
	}

	/**
	 * Builder for test RepositoryState with sensible defaults for a "good"
	 * public repo.
	 */
	private static class StateBuilder {

		private String detailsJson = GOOD_DETAILS_JSON;
		private boolean vulnerabilityAlerts = true;
		private boolean automatedSecurityFixes = false;
		private String branchProtectionJson = GOOD_BRANCH_PROTECTION_JSON;
		private boolean hasBranchProtection = true;
		private Map<String, ActualBranchProtection> extraBranchProtections = Map
				.of();
		private List<ActualSecret> actionSecrets = List.of();
		private Map<String, List<ActualSecret>> environmentSecrets = Map.of();
		private String workflowPermissionsJson = GOOD_WORKFLOW_PERMISSIONS_JSON;
		private List<ActualRuleset> rulesets = List.of();
		private Optional<ActualPages> pages = Optional.empty();
		private Map<String, ActualEnvironment> environments = Map.of();
		private boolean immutableReleases = false;

		StateBuilder detailsOverride(String overridesJson) {
			this.detailsJson = merge(this.detailsJson, overridesJson)
					.toString();
			return this;
		}

		StateBuilder vulnerabilityAlerts(boolean value) {
			this.vulnerabilityAlerts = value;
			return this;
		}

		StateBuilder automatedSecurityFixes(boolean value) {
			this.automatedSecurityFixes = value;
			return this;
		}

		StateBuilder noBranchProtection() {
			this.hasBranchProtection = false;
			return this;
		}

		StateBuilder branchProtectionOverride(String overridesJson) {
			this.branchProtectionJson = merge(
					this.branchProtectionJson,
					overridesJson
			).toString();
			return this;
		}

		StateBuilder branchProtections(String... patterns) {
			var map = new java.util.HashMap<String, ActualBranchProtection>();
			if (hasBranchProtection) {
				map.put(
						"main",
						ActualTypes.branchProtection(
								parse(
										branchProtectionJson,
										BranchProtectionResponse.class
								)
						)
				);
			}
			for (var pattern : patterns) {
				map.put(
						pattern,
						ActualTypes.branchProtection(
								parse("{}", BranchProtectionResponse.class)
						)
				);
			}
			this.extraBranchProtections = Map.copyOf(map);
			return this;
		}

		StateBuilder actionSecretNames(String... names) {
			this.actionSecrets = java.util.Arrays.stream(names)
					.map(n -> new ActualSecret(n, null))
					.toList();
			return this;
		}

		StateBuilder environmentSecretNames(
				Map<String, List<String>> envSecrets
		) {
			var converted = new java.util.LinkedHashMap<String, List<ActualSecret>>();
			envSecrets.forEach(
					(env, names) -> converted.put(
							env,
							names.stream()
									.map(n -> new ActualSecret(n, null))
									.toList()
					)
			);
			this.environmentSecrets = converted;
			return this;
		}

		StateBuilder workflowPermissions(String json) {
			this.workflowPermissionsJson = json;
			return this;
		}

		StateBuilder rulesets(List<ActualRuleset> rulesets) {
			this.rulesets = rulesets;
			return this;
		}

		StateBuilder pages(Optional<ActualPages> pages) {
			this.pages = pages;
			return this;
		}

		StateBuilder environments(Map<String, ActualEnvironment> environments) {
			this.environments = environments;
			return this;
		}

		StateBuilder immutableReleases(boolean value) {
			this.immutableReleases = value;
			return this;
		}

		RepositoryState build() {
			var bpMap = new java.util.HashMap<String, ActualBranchProtection>();
			if (hasBranchProtection) {
				bpMap.put(
						"main",
						ActualTypes.branchProtection(
								parse(
										branchProtectionJson,
										BranchProtectionResponse.class
								)
						)
				);
			}
			bpMap.putAll(extraBranchProtections);
			var details = parse(detailsJson, RepositoryDetailsResponse.class);
			return new RepositoryState(
					"repo",
					ActualTypes.repository(details),
					ActualTypes.securityAndAnalysis(details),
					vulnerabilityAlerts,
					automatedSecurityFixes,
					immutableReleases,
					false,
					false,
					Map.copyOf(bpMap),
					rulesets,
					actionSecrets,
					environments,
					environmentSecrets,
					ActualTypes.workflowPermissions(
							parse(
									workflowPermissionsJson,
									WorkflowPermissions.class
							)
					),
					pages
			);
		}

	}

	// ─── No-drift tests
	// ──────────────────────────────────────────────────────

	@Test
	void noDrift_forCorrectPublicRepo() {
		var groupDrifts = computeGroupDrifts(goodPublicState(), defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).isEmpty();
	}

	@Test
	void noDrift_forCorrectArchivedRepo() {
		var state = new StateBuilder().detailsOverride("""
				{"archived": true}
				""")
				.vulnerabilityAlerts(false)
				.automatedSecurityFixes(false)
				.noBranchProtection()
				.workflowPermissions("""
						{
							"default_workflow_permissions": "read",
							"can_approve_pull_request_reviews": false
						}
						""")
				.build();
		var groupDrifts = computeGroupDrifts(
				state,
				defaultDesired().withArchived(true).withBranchProtections(Map.of())
		);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).isEmpty();
	}

	// Security settings (migrated to DriftGroups - see *DriftGroupTest classes)

	// ─── Archived / private
	// ──────────────────────────────────────────────────

	@Test
	void archived_skipsSecurityAndBranchProtectionChecks() {
		var state = new StateBuilder().detailsOverride("""
				{"archived": true}
				""")
				.vulnerabilityAlerts(false)
				.automatedSecurityFixes(false)
				.detailsOverride(
						"""
								{
									"security_and_analysis": {
										"secret_scanning": {"status": "disabled"},
										"secret_scanning_push_protection": {"status": "disabled"}
									}
								}
								"""
				)
				.noBranchProtection()
				.workflowPermissions("""
						{
							"default_workflow_permissions": "read",
							"can_approve_pull_request_reviews": false
						}
						""")
				.build();
		var groupDrifts = computeGroupDrifts(
				state,
				defaultDesired().withArchived(true).withBranchProtections(Map.of())
		);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).isEmpty();
	}

	@Test
	void drift_whenActualArchivedButDesiredNot() {
		var state = new StateBuilder().detailsOverride("""
				{"archived": true}
				""")
				.vulnerabilityAlerts(false)
				.automatedSecurityFixes(false)
				.noBranchProtection()
				.build();
		var groupDrifts = computeGroupDrifts(
				state,
				defaultDesired().withBranchProtections(Map.of())
		);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains("archived: want=false got=true");
	}

	// ─── Branch protection drift (tested via groupDrifts below)
	// ──────────────────────────────────────────────

	// ─── Workflow permissions drift (tested via groupDrifts in
	// OrgCheckerDiffTest)
	// ──────────────────────────────────────────

	@Test
	void drift_workflowPermissionsDefault_isRead() {
		var state = new StateBuilder().workflowPermissions("""
				{
					"default_workflow_permissions": "read",
					"can_approve_pull_request_reviews": true
				}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var groupDriftMessages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(groupDriftMessages)
				.contains("workflow_permissions.default: want=WRITE got=READ");
	}

	@Test
	void drift_canApprovePullRequestReviews_isFalse() {
		var state = new StateBuilder().workflowPermissions("""
				{
					"default_workflow_permissions": "write",
					"can_approve_pull_request_reviews": false
				}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var groupDriftMessages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(groupDriftMessages).contains(
				"workflow_permissions.can_approve_prs: want=true got=false"
		);
	}

	// ─── Branch protection drift (tested via groupDrifts)
	// ──────────────────────────────────────────────

	@Test
	void drift_branchProtectionMissing() {
		var state = new StateBuilder().noBranchProtection().build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains("branch_protection.main: missing");
	}

	@Test
	void drift_enforceAdmins_isFalse() {
		var state = new StateBuilder().branchProtectionOverride("""
				{"enforce_admins": {"enabled": false}}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.main.enforce_admins: want=true got=false"
		);
	}

	@Test
	void drift_requiredLinearHistory_isFalse() {
		var state = new StateBuilder().branchProtectionOverride("""
				{"required_linear_history": {"enabled": false}}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.main.required_linear_history: want=true got=false"
		);
	}

	@Test
	void drift_allowForcePushes_isTrue() {
		var state = new StateBuilder().branchProtectionOverride("""
				{"allow_force_pushes": {"enabled": true}}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.main.allow_force_pushes: want=false got=true"
		);
	}

	@Test
	void drift_requiredStatusChecksStrict_isTrue() {
		var state = new StateBuilder()
				.branchProtectionOverride(
						"""
								{
									"required_status_checks": {
										"strict": true,
										"checks": [
											{"context": "check-actions.required-status-check"},
											{"context": "codeql-analysis.required-status-check"},
											{"context": "CodeQL"},
											{"context": "dependency-graph-sarif"}
										]
									}
								}
								"""
				)
				.build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.main.required_status_checks.strict: want=false got=true"
		);
	}

	@Test
	void drift_requiredStatusChecks_differ() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"required_status_checks": {
						"strict": false,
						"checks": [
							{"context": "existing-check"}
						]
					}
				}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).anyMatch(
				d -> d.contains("branch_protection.main.required_status_checks")
						&& d.contains("extra")
		);
	}

	@Test
	void drift_pullRequestReviewsMissing() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"required_pull_request_reviews": {
						"dismiss_stale_reviews": false,
						"require_code_owner_reviews": false
					}
				}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.main.required_pull_request_reviews.dismiss_stale_reviews: want=true got=false"
		);
	}

	@Test
	void drift_requiredApprovingReviewCount_differ() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"required_pull_request_reviews": {
						"dismiss_stale_reviews": false,
						"require_code_owner_reviews": false,
						"required_approving_review_count": 2
					}
				}
				""").build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.main.required_pull_request_reviews.required_approving_review_count: want=null got=2"
		);
	}

	@Test
	void drift_restrictionsMissing() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"restrictions": null
				}
				""").build();
		var args = defaultDesired().withBranchProtections(
				Map.of(
						"main",
						Desired.branchProtection()
								.withEnforceAdmins(true)
								.withRequiredLinearHistory(true)
								.withDismissStaleReviews(true)
								.withUsers(List.of("admin"))
				)
		);
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages)
				.contains("branch_protection.main.restrictions: missing");
	}

	@Test
	void drift_restrictions_usersDiffer() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"restrictions": {
						"users": [
							{"login": "alice"},
							{"login": "bob"}
						],
						"teams": [],
						"apps": []
					}
				}
				""").build();
		var args = defaultDesired().withBranchProtections(
				Map.of(
						"main",
						Desired.branchProtection()
								.withEnforceAdmins(true)
								.withRequiredLinearHistory(true)
								.withUsers(List.of("charlie"))
				)
		);
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).anyMatch(
				d -> d.contains("branch_protection.main.restrictions.users")
						&& d.contains("missing: [charlie]")
		);
	}

	@Test
	void drift_restrictions_teamsDiffer() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"restrictions": {
						"users": [],
						"teams": [
							{"slug": "eng"},
							{"slug": "docs"}
						],
						"apps": []
					}
				}
				""").build();
		var args = defaultDesired().withBranchProtections(
				Map.of(
						"main",
						Desired.branchProtection()
								.withEnforceAdmins(true)
								.withRequiredLinearHistory(true)
								.withTeams(List.of("platform"))
				)
		);
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).anyMatch(
				d -> d.contains("branch_protection.main.restrictions.teams")
						&& d.contains("missing: [platform]")
		);
	}

	@Test
	void drift_restrictions_appsDiffer() {
		var state = new StateBuilder().branchProtectionOverride("""
				{
					"restrictions": {
						"users": [],
						"teams": [],
						"apps": [
							{"slug": "my-app"}
						]
					}
				}
				""").build();
		var args = defaultDesired().withBranchProtections(
				Map.of(
						"main",
						Desired.branchProtection()
								.withEnforceAdmins(true)
								.withRequiredLinearHistory(true)
								.withApps(List.of("other-app"))
				)
		);
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).anyMatch(
				d -> d.contains("branch_protection.main.restrictions.apps")
						&& d.contains("missing: [other-app]")
		);
	}

	@Test
	void drift_branchProtectionExtra() {
		var state = new StateBuilder().branchProtectionOverride("{}")
				.branchProtections("staging")
				.build();
		var groupDrifts = computeGroupDrifts(state, defaultDesired());
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"branch_protection.staging: extra (should not exist)"
		);
	}

	// ─── No-drift (matching) tests
	// ──────────────────────────────────────────

	@Test
	void existingEnvironmentSecret_withoutBaseline_isDrift() {
		var args = Desired.withEnvironment(
				defaultDesired(),
				"production",
				Desired.environment().withSecrets(List.of("TF_GITHUB_TOKEN"))
		);
		var state = new StateBuilder()
				.environmentSecretNames(
						Map.of("production", List.of("TF_GITHUB_TOKEN"))
				)
				.environments(
						Map.of(
								"production",
								new ActualEnvironment(0, false, false)
						)
				)
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).containsExactly(
				"environment_secrets.production.secrets.TF_GITHUB_TOKEN: exists but "
						+ "has no recorded baseline (--fix pushes the configured value)"
		);
	}

	@Test
	void existingActionSecret_withoutBaseline_isDrift() {
		var args = defaultDesired().withActionsSecrets(List.of("PAT"));
		var state = new StateBuilder().actionSecretNames("PAT").build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).containsExactly(
				"action_secrets.PAT: exists but has no recorded baseline "
						+ "(--fix pushes the configured value)"
		);
	}

	// ─── Pages
	// ──────────────────────────────────────────────────────────

	@Test
	void pages_noDrift_whenEnvironmentAndPagesPresent() {
		var args = defaultDesired().withPages(Desired.pages());
		var state = new StateBuilder()
				.environmentSecretNames(Map.of("github-pages", List.of()))
				.pages(Optional.of(goodPages()))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).isEmpty();
	}

	private static ActualPages goodPages() {
		return new ActualPages("workflow", Optional.empty(), true);
	}

	// ─── Rulesets drift
	// ──────────────────────────────────────────────────────

	private static Rule ruleFromType(RulesetRuleType type) {
		return switch (type) {
		case REQUIRED_LINEAR_HISTORY -> new Rule.RequiredLinearHistory();
		case NON_FAST_FORWARD -> new Rule.NonFastForward();
		default ->
			new Rule.Unknown(type.name().toLowerCase(java.util.Locale.ROOT));
		};
	}

	private static ActualRuleset rulesetWithRules(
			String name,
			RulesetRuleType... ruleTypes
	) {
		var include = List.of("~DEFAULT_BRANCH");
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						include,
						List.of()
				),
				null,
				null,
				null
		);
		List<Rule> rules = new java.util.ArrayList<>();
		for (RulesetRuleType type : ruleTypes) {
			rules.add(ruleFromType(type));
		}
		return ActualTypes.ruleset(
				new RulesetDetailsResponse(
						1L,
						name,
						RulesetTarget.BRANCH,
						RulesetEnforcement.ACTIVE,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						conditions,
						rules
				)
		);
	}

	private static ActualRuleset rulesetWithStatusChecks(
			String name,
			String... contexts
	) {
		var include = List.of("~DEFAULT_BRANCH");
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						include,
						List.of()
				),
				null,
				null,
				null
		);
		List<Rule.StatusCheck> checks = new java.util.ArrayList<>();
		for (String ctx : contexts) {
			checks.add(new Rule.StatusCheck(ctx, null));
		}
		return ActualTypes.ruleset(
				new RulesetDetailsResponse(
						1L,
						name,
						RulesetTarget.BRANCH,
						RulesetEnforcement.ACTIVE,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						conditions,
						List.of(
								new Rule.RequiredLinearHistory(),
								new Rule.NonFastForward(),
								new Rule.RequiredStatusChecks(
										new Rule.RequiredStatusChecks.Parameters(
												checks,
												false
										)
								)
						)
				)
		);
	}

	@Test
	void noDrift_rulesetMatchesExactly() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredLinearHistory(true)
								.withNoForcePushes(true)
								.withRequiredStatusChecks(
										List.of(
												Desired.statusCheck(
														"check-actions.required-status-check"
												)
										)
								)
				)
		);
		var state = new StateBuilder()
				.rulesets(
						List.of(
								rulesetWithStatusChecks(
										"main-branch-rules",
										"check-actions.required-status-check"
								)
						)
				)
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).isEmpty();
	}

	// ─── Rulesets drift (tested via groupDrifts)

	@Test
	void drift_rulesetMissing() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
				)
		);
		var state = new StateBuilder().rulesets(List.of()).build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains("rulesets.main-branch-rules: missing");
	}

	@Test
	void drift_rulesetLinearHistoryMissing() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredLinearHistory(true)
				)
		);
		var state = new StateBuilder()
				.rulesets(List.of(rulesetWithRules("main-branch-rules")))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"rulesets.main-branch-rules.required_linear_history: want=true got=false"
		);
	}

	@Test
	void drift_rulesetNoForcePushesMissing() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withNoForcePushes(true)
				)
		);
		var state = new StateBuilder()
				.rulesets(List.of(rulesetWithRules("main-branch-rules")))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"rulesets.main-branch-rules.no_force_pushes: want=true got=false"
		);
	}

	@Test
	void drift_rulesetStatusCheckMissing() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredStatusChecks(
										List.of(
												Desired.statusCheck("CodeQL"),
												Desired.statusCheck("zizmor")
										)
								)
				)
		);
		var state = new StateBuilder().rulesets(
				List.of(rulesetWithStatusChecks("main-branch-rules", "CodeQL"))
		).build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).anyMatch(
				d -> d.contains(
						"rulesets.main-branch-rules.required_status_checks"
				) && d.contains("missing") && d.contains("zizmor")
		);
	}

	@Test
	void drift_rulesetExtraStatusCheck() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredStatusChecks(
										List.of(Desired.statusCheck("CodeQL"))
								)
				)
		);
		var state = new StateBuilder()
				.rulesets(
						List.of(
								rulesetWithStatusChecks(
										"main-branch-rules",
										"CodeQL",
										"unexpected-check"
								)
						)
				)
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).anyMatch(
				d -> d.contains(
						"rulesets.main-branch-rules.required_status_checks"
				) && d.contains("extra") && d.contains("unexpected-check")
		);
	}

	// ─── Environment config drift
	// ──────────────────────────────────────────

	@Test
	void drift_rulesetRequiredReviewCountWrong() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredReviewCount(2L)
				)
		);
		var include = List.of("~DEFAULT_BRANCH");
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						include,
						List.of()
				),
				null,
				null,
				null
		);
		var actualRuleset = new RulesetDetailsResponse(
				1L,
				"main-branch-rules",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				conditions,
				List.of(
						new Rule.PullRequest(
								new Rule.PullRequest.Parameters(
										1,
										false,
										false,
										false
								)
						)
				)
		);
		var state = new StateBuilder()
				.rulesets(List.of(ActualTypes.ruleset(actualRuleset)))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"rulesets.main-branch-rules.required_review_count: want=2 got=1"
		);
	}

	@Test
	void noDrift_codeScanningMatchesExactly() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredLinearHistory(true)
								.withRequiredCodeScanning(
										List.of(
												Desired.codeScanningTool(
														"CodeQL"
												)
										)
								)
				)
		);
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						List.of("~DEFAULT_BRANCH"),
						List.of()
				),
				null,
				null,
				null
		);
		var actualRuleset = new RulesetDetailsResponse(
				1L,
				"main-branch-rules",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				conditions,
				List.of(
						new Rule.RequiredLinearHistory(),
						new Rule.CodeScanning(
								new Rule.CodeScanning.Parameters(
										List.of(
												new Rule.CodeScanningTool(
														"CodeQL",
														Rule.AlertsThreshold.NONE,
														Rule.SecurityAlertsThreshold.NONE
												)
										)
								)
						)
				)
		);
		var state = new StateBuilder()
				.rulesets(List.of(ActualTypes.ruleset(actualRuleset)))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).isEmpty();
	}

	@Test
	void drift_codeScanningMissing() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
								.withRequiredCodeScanning(
										List.of(
												Desired.codeScanningTool(
														"CodeQL"
												)
										)
								)
				)
		);
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						List.of("~DEFAULT_BRANCH"),
						List.of()
				),
				null,
				null,
				null
		);
		var actualRuleset = new RulesetDetailsResponse(
				1L,
				"main-branch-rules",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				conditions,
				List.of()
		);
		var state = new StateBuilder()
				.rulesets(List.of(ActualTypes.ruleset(actualRuleset)))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"rulesets.main-branch-rules.required_code_scanning missing: [CodeQL]"
		);
	}

	@Test
	void drift_codeScanningExtraTool() {
		var args = defaultDesired().withRulesets(
				Map.of(
						"main-branch-rules",
						Desired.ruleset()
								.withIncludePatterns(List.of("~DEFAULT_BRANCH"))
				)
		);
		var conditions = new RulesetDetailsResponse.Conditions(
				new RulesetDetailsResponse.Conditions.RefName(
						List.of("~DEFAULT_BRANCH"),
						List.of()
				),
				null,
				null,
				null
		);
		var actualRuleset = new RulesetDetailsResponse(
				1L,
				"main-branch-rules",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				conditions,
				List.of(
						new Rule.CodeScanning(
								new Rule.CodeScanning.Parameters(
										List.of(
												new Rule.CodeScanningTool(
														"CodeQL",
														Rule.AlertsThreshold.NONE,
														Rule.SecurityAlertsThreshold.NONE
												)
										)
								)
						)
				)
		);
		var state = new StateBuilder()
				.rulesets(List.of(ActualTypes.ruleset(actualRuleset)))
				.build();
		var groupDrifts = computeGroupDrifts(state, args);
		var messages = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
		assertThat(messages).contains(
				"rulesets.main-branch-rules.required_code_scanning extra: [CodeQL]"
		);
	}

}
