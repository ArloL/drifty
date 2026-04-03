package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.interfaces.Box;

import io.github.arlol.githubcheck.client.BranchProtectionRequest;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.EnvironmentUpdateRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesCreateRequest;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.PagesUpdateRequest;
import io.github.arlol.githubcheck.client.RepositoryMinimal;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.client.RulesetRuleType;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.SecretPublicKeyResponse;
import io.github.arlol.githubcheck.client.SecretRequest;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.config.BranchProtectionArgs;
import io.github.arlol.githubcheck.config.BypassActorArgs;
import io.github.arlol.githubcheck.config.CodeScanningToolArgs;
import io.github.arlol.githubcheck.config.EnvironmentArgs;
import io.github.arlol.githubcheck.config.PagesArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulePatternArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

public class OrgChecker {

	private final GitHubClient client;
	private final String org;
	private final boolean fix;
	private final Map<String, String> githubSecrets;

	public OrgChecker(String token, String org) {
		this(new GitHubClient(token), org, false, Map.of());
	}

	public OrgChecker(String token, String org, boolean fix) {
		this(new GitHubClient(token), org, fix, Map.of());
	}

	public OrgChecker(
			String token,
			String org,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this(new GitHubClient(token), org, fix, githubSecrets);
	}

	OrgChecker(GitHubClient client, String org) {
		this(client, org, false, Map.of());
	}

	OrgChecker(GitHubClient client, String org, boolean fix) {
		this(client, org, fix, Map.of());
	}

	OrgChecker(
			GitHubClient client,
			String org,
			boolean fix,
			Map<String, String> githubSecrets
	) {
		this.client = client;
		this.org = org;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
	}

	public CheckResult check(List<RepositoryArgs> repositories)
			throws IOException, InterruptedException, ExecutionException {
		System.out.println("Fetching repo list for org: " + org);
		List<RepositoryMinimal> summaries = client.listOrgRepos(org);
		System.out.printf(
				"Found %d repos. Fetching details in parallel...%n",
				summaries.size()
		);

		long startFetch = System.currentTimeMillis();

		Map<String, RepositoryArgs> desiredByName = repositories.stream()
				.collect(Collectors.toMap(RepositoryArgs::name, r -> r));

		List<CheckResult.RepoCheckResult> results = new ArrayList<>();

		try (ExecutorService executor = Executors
				.newVirtualThreadPerTaskExecutor()) {
			List<Future<CheckResult.RepoCheckResult>> futures = summaries
					.stream()
					.map(
							summary -> executor.submit(
									() -> checkOne(summary, desiredByName)
							)
					)
					.toList();
			for (Future<CheckResult.RepoCheckResult> f : futures) {
				results.add(f.get());
			}
		}

		// Repos declared in config but not found in the org
		Set<String> foundNames = summaries.stream()
				.map(RepositoryMinimal::name)
				.collect(Collectors.toSet());
		repositories.stream()
				.filter(r -> !foundNames.contains(r.name()))
				.map(r -> CheckResult.RepoCheckResult.missing(r.name()))
				.forEach(results::add);

		double fetchSeconds = (System.currentTimeMillis() - startFetch)
				/ 1000.0;
		System.out.printf("Fetch complete in %.2f seconds%n%n", fetchSeconds);

		return new CheckResult(Collections.unmodifiableList(results));
	}

	private CheckResult.RepoCheckResult checkOne(
			RepositoryMinimal summary,
			Map<String, RepositoryArgs> desiredByName
	) {
		String name = summary.name();
		RepositoryArgs desired = desiredByName.get(name);
		if (desired == null) {
			return CheckResult.RepoCheckResult.unknown(name);
		}
		try {
			RepositoryState state = fetchState(summary);
			List<String> diffs = computeDiffs(state, desired);
			if (fix) {
				diffs = applyFixes(name, state, desired, diffs);
			}
			return diffs.isEmpty() ? CheckResult.RepoCheckResult.ok(name)
					: CheckResult.RepoCheckResult.drift(name, diffs);
		} catch (IOException | InterruptedException e) {
			return CheckResult.RepoCheckResult.error(name, e.getMessage());
		}
	}

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	RepositoryState fetchState(RepositoryMinimal summary)
			throws IOException, InterruptedException {
		String name = summary.name();
		boolean archived = summary.archived();

		var details = client.getRepo(org, name);

		boolean vulnAlerts = false;
		boolean automatedSecurityFixes = false;
		boolean immutableReleases = false;
		boolean privateVulnerabilityReporting = false;
		boolean codeScanningDefaultSetup = false;
		if (!archived) {
			vulnAlerts = client.getVulnerabilityAlerts(org, name);
			automatedSecurityFixes = client
					.getAutomatedSecurityFixes(org, name);
			var ir = client.getImmutableReleases(org, name);
			if (ir.isPresent()) {
				immutableReleases = ir.orElseThrow().enabled();
			}
			privateVulnerabilityReporting = client
					.getPrivateVulnerabilityReporting(org, name);
			codeScanningDefaultSetup = client
					.getCodeScanningDefaultSetup(org, name);
		}

		Map<String, BranchProtectionResponse> branchProtections = new HashMap<>();
		if (!archived && RepositoryVisibility.PUBLIC == summary.visibility()) {
			var branches = client.getBranches(org, name, true);
			for (var branch : branches) {
				var bp = client.getBranchProtection(org, name, branch.name());
				branchProtections.put(branch.name(), bp.orElseThrow());
			}
		}

		List<String> secretNames = client.getActionSecretNames(org, name);
		List<EnvironmentDetailsResponse> environments = client
				.getEnvironments(org, name);

		Map<String, List<String>> envSecrets = new LinkedHashMap<>();
		Map<String, EnvironmentDetailsResponse> envDetails = new LinkedHashMap<>();
		for (EnvironmentDetailsResponse env : environments) {
			envDetails.put(env.name(), env);
			envSecrets.put(
					env.name(),
					client.getEnvironmentSecretNames(org, name, env.name())
			);
		}

		WorkflowPermissions wfPerms = client.getWorkflowPermissions(org, name);

		List<RulesetDetailsResponse> rulesets;
		if (archived) {
			rulesets = List.of();
		} else {
			var rulesetSummaries = client.listRulesets(org, name);
			rulesets = new ArrayList<>();
			for (var rs : rulesetSummaries) {
				rulesets.add(client.getRuleset(org, name, rs.id()));
			}
		}

		Optional<PagesResponse> pages = archived ? Optional.empty()
				: client.getPages(org, name);

		return new RepositoryState(
				name,
				summary,
				details,
				vulnAlerts,
				automatedSecurityFixes,
				branchProtections,
				secretNames,
				envSecrets,
				wfPerms,
				rulesets,
				pages,
				envDetails,
				immutableReleases,
				privateVulnerabilityReporting,
				codeScanningDefaultSetup
		);
	}

	// ─── Diff
	// ──────────────────────────────────────────────────────────────

	List<String> computeDiffs(RepositoryState actual, RepositoryArgs desired) {
		List<String> diffs = new ArrayList<>();

		if (desired.archived()) {
			if (actual.summary().archived()) {
				return List.of();
			} else {
				return List.of("archived");
			}
		}

		checkRepoSettings(diffs, actual, desired);
		checkTopics(diffs, actual, desired);
		check(
				diffs,
				"default_branch",
				desired.defaultBranch(),
				actual.details().defaultBranch()
		);
		checkSecuritySettings(diffs, actual, desired);
		checkWorkflowPermissions(diffs, actual, desired);
		checkBranchProtection(diffs, actual, desired);
		checkRulesets(diffs, actual, desired);
		checkPages(diffs, actual, desired);
		checkSecrets(diffs, actual, desired);
		checkEnvironmentConfig(diffs, actual, desired);

		return diffs;
	}

	private void checkRepoSettings(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		var details = actual.details();
		check(
				diffs,
				"archived",
				desired.archived(),
				actual.summary().archived()
		);
		check(
				diffs,
				"description",
				desired.description(),
				Objects.toString(details.description(), "")
		);
		check(
				diffs,
				"homepage_url",
				desired.homepageUrl(),
				Objects.toString(details.homepage(), "")
		);
		check(diffs, "has_issues", desired.hasIssues(), details.hasIssues());
		check(
				diffs,
				"has_projects",
				desired.hasProjects(),
				details.hasProjects()
		);
		check(diffs, "has_wiki", desired.hasWiki(), details.hasWiki());
		check(
				diffs,
				"has_discussions",
				desired.hasDiscussions(),
				details.hasDiscussions()
		);
		check(diffs, "is_template", desired.isTemplate(), details.isTemplate());
		check(
				diffs,
				"allow_forking",
				desired.allowForking(),
				details.allowForking()
		);
		check(
				diffs,
				"web_commit_signoff_required",
				desired.webCommitSignoffRequired(),
				details.webCommitSignoffRequired()
		);
		check(
				diffs,
				"allow_merge_commit",
				desired.allowMergeCommit(),
				details.allowMergeCommit()
		);
		check(
				diffs,
				"allow_squash_merge",
				desired.allowSquashMerge(),
				details.allowSquashMerge()
		);
		check(
				diffs,
				"allow_rebase_merge",
				desired.allowRebaseMerge(),
				details.allowRebaseMerge()
		);
		check(
				diffs,
				"allow_auto_merge",
				desired.allowAutoMerge(),
				details.allowAutoMerge()
		);
		check(
				diffs,
				"allow_update_branch",
				desired.allowUpdateBranch(),
				details.allowUpdateBranch()
		);
		check(
				diffs,
				"delete_branch_on_merge",
				desired.deleteBranchOnMerge(),
				details.deleteBranchOnMerge()
		);
		check(
				diffs,
				"squash_merge_commit_title",
				desired.squashMergeCommitTitle(),
				details.squashMergeCommitTitle()
		);
		check(
				diffs,
				"squash_merge_commit_message",
				desired.squashMergeCommitMessage(),
				details.squashMergeCommitMessage()
		);
		check(
				diffs,
				"merge_commit_title",
				desired.mergeCommitTitle(),
				details.mergeCommitTitle()
		);
		check(
				diffs,
				"merge_commit_message",
				desired.mergeCommitMessage(),
				details.mergeCommitMessage()
		);
		check(diffs, "visibility", desired.visibility(), details.visibility());
		check(
				diffs,
				"default_branch",
				desired.defaultBranch(),
				details.defaultBranch()
		);
	}

	private void checkTopics(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		List<String> topics = actual.details().topics();
		if (topics == null) {
			topics = List.of();
		}
		checkSets(
				diffs,
				"topics",
				new HashSet<>(desired.topics()),
				new HashSet<>(topics)
		);
	}

	private void checkSecuritySettings(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		check(
				diffs,
				"vulnerability_alerts",
				desired.vulnerabilityAlerts(),
				actual.vulnerabilityAlerts()
		);
		check(
				diffs,
				"automated_security_fixes",
				desired.automatedSecurityFixes(),
				actual.automatedSecurityFixes()
		);
		check(
				diffs,
				"immutable_releases",
				desired.immutableReleases(),
				actual.immutableReleases()
		);
		var sa = actual.details().securityAndAnalysis();
		if (sa != null) {
			boolean secretScanning = sa.secretScanning() != null
					&& SecurityAndAnalysis.StatusObject.Status.ENABLED
							.equals(sa.secretScanning().status());
			boolean secretScanningPush = sa
					.secretScanningPushProtection() != null
					&& SecurityAndAnalysis.StatusObject.Status.ENABLED
							.equals(sa.secretScanningPushProtection().status());
			check(
					diffs,
					"secret_scanning",
					desired.secretScanning(),
					secretScanning
			);
			check(
					diffs,
					"secret_scanning_push_protection",
					desired.secretScanningPushProtection(),
					secretScanningPush
			);
			boolean secretScanningValidityChecks = sa
					.secretScanningValidityChecks() != null
					&& SecurityAndAnalysis.StatusObject.Status.ENABLED
							.equals(sa.secretScanningValidityChecks().status());
			boolean secretScanningNonProviderPatterns = sa
					.secretScanningNonProviderPatterns() != null
					&& SecurityAndAnalysis.StatusObject.Status.ENABLED.equals(
							sa.secretScanningNonProviderPatterns().status()
					);
			check(
					diffs,
					"secret_scanning_validity_checks",
					desired.secretScanningValidityChecks(),
					secretScanningValidityChecks
			);
			check(
					diffs,
					"secret_scanning_non_provider_patterns",
					desired.secretScanningNonProviderPatterns(),
					secretScanningNonProviderPatterns
			);
		}
		check(
				diffs,
				"private_vulnerability_reporting",
				desired.privateVulnerabilityReporting(),
				actual.privateVulnerabilityReporting()
		);
		check(
				diffs,
				"code_scanning_default_setup",
				desired.codeScanningDefaultSetup(),
				actual.codeScanningDefaultSetup()
		);
	}

	private void checkWorkflowPermissions(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		check(
				diffs,
				"workflow_permissions.default",
				desired.defaultWorkflowPermissions(),
				actual.workflowPermissions().defaultWorkflowPermissions()
		);
		check(
				diffs,
				"workflow_permissions.can_approve_prs",
				desired.canApprovePullRequestReviews(),
				actual.workflowPermissions().canApprovePullRequestReviews()
		);
	}

	private void checkBranchProtection(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		var wantedBps = desired.branchProtections().values();
		var actualBps = new HashMap<>(actual.branchProtections());

		if (actualBps.isEmpty()) {
			for (BranchProtectionArgs wanted : wantedBps) {
				diffs.add(
						"branch_protection." + wanted.pattern() + ": missing"
				);
			}
			return;
		}

		for (BranchProtectionArgs wanted : wantedBps) {
			String prefix = "branch_protection." + wanted.pattern();

			var actualBp = actualBps.remove(wanted.pattern());
			if (actualBp == null) {
				diffs.add(prefix + ": missing");
				continue;
			}

			check(
					diffs,
					prefix + ".enforce_admins",
					wanted.enforceAdmins(),
					actualBp.enforceAdmins().enabled()
			);
			check(
					diffs,
					prefix + ".required_linear_history",
					wanted.requiredLinearHistory(),
					actualBp.requiredLinearHistory().enabled()
			);
			check(
					diffs,
					prefix + ".allow_force_pushes",
					wanted.allowForcePushes(),
					actualBp.allowForcePushes().enabled()
			);
			check(
					diffs,
					prefix + ".require_conversation_resolution",
					wanted.requireConversationResolution(),
					actualBp.requiredConversationResolution() != null
							&& actualBp.requiredConversationResolution()
									.enabled()
			);

			var rsc = actualBp.requiredStatusChecks();
			boolean strict = rsc != null && rsc.strict();
			Set<StatusCheckArgs> wantedChecks = wanted.requiredStatusChecks();
			check(
					diffs,
					prefix + ".required_status_checks.strict",
					false,
					strict
			);

			List<StatusCheckArgs> statusChecks = List.of();
			if (rsc != null) {
				var checks = rsc.checks();
				if (checks != null && !checks.isEmpty()) {
					statusChecks = checks.stream()
							.map(
									c -> StatusCheckArgs.builder()
											.context(c.context())
											.appId(c.appId())
											.build()
							)
							.toList();
				} else {
					var contexts = rsc.contexts();
					if (contexts != null) {
						statusChecks = contexts.stream()
								.map(
										c -> StatusCheckArgs.builder()
												.context(c)
												.build()
								)
								.toList();
					}
				}
			}

			checkStatusCheckSets(
					diffs,
					prefix + ".required_status_checks",
					new HashSet<>(wantedChecks),
					new HashSet<>(statusChecks)
			);

			var rpr = actualBp.requiredPullRequestReviews();
			if (rpr == null) {
				if (wanted.dismissStaleReviews()
						|| wanted.requireCodeOwnerReviews()
						|| wanted.requiredApprovingReviewCount() != null
						|| wanted.requireLastPushApproval() != null) {
					diffs.add(
							prefix + ".required_pull_request_reviews: missing"
					);
				}
			} else {
				check(
						diffs,
						prefix + ".required_pull_request_reviews.dismiss_stale_reviews",
						wanted.dismissStaleReviews(),
						rpr.dismissStaleReviews()
				);
				check(
						diffs,
						prefix + ".required_pull_request_reviews.require_code_owner_reviews",
						wanted.requireCodeOwnerReviews(),
						rpr.requireCodeOwnerReviews()
				);
				Integer wantCount = wanted.requiredApprovingReviewCount();
				Integer actualCount = rpr.requiredApprovingReviewCount();
				if (wantCount == null && actualCount != null) {
					diffs.add(
							prefix + ".required_pull_request_reviews.required_approving_review_count: drifted"
					);
				} else if (wantCount != null
						&& !wantCount.equals(actualCount)) {
					diffs.add(
							prefix + ".required_pull_request_reviews.required_approving_review_count: drifted"
					);
				}
				Boolean wantLastPush = wanted.requireLastPushApproval();
				Boolean actualLastPush = rpr.requireLastPushApproval();
				if (wantLastPush == null && actualLastPush != null
						&& actualLastPush) {
					diffs.add(
							prefix + ".required_pull_request_reviews.require_last_push_approval: drifted"
					);
				} else if (wantLastPush != null
						&& !wantLastPush.equals(actualLastPush)) {
					diffs.add(
							prefix + ".required_pull_request_reviews.require_last_push_approval: drifted"
					);
				}
			}

			var restrictions = actualBp.restrictions();
			if (restrictions == null) {
				if (!wanted.users().isEmpty() || !wanted.teams().isEmpty()
						|| !wanted.apps().isEmpty()) {
					diffs.add(prefix + ".restrictions: missing");
				}
			} else {
				Set<String> wantUsers = new HashSet<>(wanted.users());
				Set<String> actualUsers = restrictions.users()
						.stream()
						.map(SimpleUser::login)
						.collect(Collectors.toSet());
				checkSets(
						diffs,
						prefix + ".restrictions.users",
						wantUsers,
						actualUsers
				);

				Set<String> wantTeams = new HashSet<>(wanted.teams());
				Set<String> actualTeams = restrictions.teams()
						.stream()
						.map(BranchProtectionResponse.Restrictions.Team::slug)
						.collect(Collectors.toSet());
				checkSets(
						diffs,
						prefix + ".restrictions.teams",
						wantTeams,
						actualTeams
				);

				Set<String> wantApps = new HashSet<>(wanted.apps());
				Set<String> actualApps = restrictions.apps()
						.stream()
						.map(BranchProtectionResponse.Restrictions.App::slug)
						.collect(Collectors.toSet());
				checkSets(
						diffs,
						prefix + ".restrictions.apps",
						wantApps,
						actualApps
				);
			}
		}

		for (var actualBpName : actualBps.keySet()) {
			diffs.add("branch_protection." + actualBpName + ": extra");
		}
	}

	private void checkRulesets(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		if (desired.rulesets().isEmpty()) {
			return;
		}

		Map<String, RulesetDetailsResponse> actualByName = actual.rulesets()
				.stream()
				.collect(
						Collectors.toMap(
								RulesetDetailsResponse::name,
								r -> r,
								(a, b) -> a
						)
				);

		for (RulesetArgs wantedRuleset : desired.rulesets()) {
			String rName = wantedRuleset.name();
			RulesetDetailsResponse actualRuleset = actualByName.get(rName);
			if (actualRuleset == null) {
				diffs.add("ruleset." + rName + ": missing");
				continue;
			}

			// Check include patterns
			Set<String> wantIncludes = new HashSet<>(
					wantedRuleset.includePatterns()
			);
			Set<String> gotIncludes = Set.of();
			if (actualRuleset.conditions() != null
					&& actualRuleset.conditions().refName() != null
					&& actualRuleset.conditions().refName().include() != null) {
				gotIncludes = new HashSet<>(
						actualRuleset.conditions().refName().include()
				);
			}
			checkSets(
					diffs,
					"ruleset." + rName + ".include_patterns",
					wantIncludes,
					gotIncludes
			);

			// Build a map of actual rules by type
			Map<RulesetRuleType, Rule> actualRulesByType = Map.of();
			if (actualRuleset.rules() != null) {
				actualRulesByType = actualRuleset.rules()
						.stream()
						.filter(r -> r.type() != null)
						.collect(
								Collectors
										.toMap(Rule::type, r -> r, (a, b) -> a)
						);
			}

			// Check required_linear_history
			boolean hasLinearHistory = actualRulesByType
					.containsKey(RulesetRuleType.REQUIRED_LINEAR_HISTORY);
			check(
					diffs,
					"ruleset." + rName + ".required_linear_history",
					wantedRuleset.requiredLinearHistory(),
					hasLinearHistory
			);

			// Check non_fast_forward (no force pushes)
			boolean hasNonFastForward = actualRulesByType
					.containsKey(RulesetRuleType.NON_FAST_FORWARD);
			check(
					diffs,
					"ruleset." + rName + ".no_force_pushes",
					wantedRuleset.noForcePushes(),
					hasNonFastForward
			);

			// Check required_status_checks
			Set<StatusCheckArgs> wantChecks = new HashSet<>(
					wantedRuleset.requiredStatusChecks()
			);
			Set<StatusCheckArgs> gotChecks = new HashSet<>();
			if (actualRulesByType.get(
					RulesetRuleType.REQUIRED_STATUS_CHECKS
			) instanceof Rule.RequiredStatusChecks rsc
					&& rsc.parameters() != null
					&& rsc.parameters().requiredStatusChecks() != null) {
				for (var sc : rsc.parameters().requiredStatusChecks()) {
					gotChecks.add(
							StatusCheckArgs.builder()
									.context(sc.context())
									.appId(sc.integrationId())
									.build()
					);
				}
			}
			if (!wantChecks.isEmpty() || !gotChecks.isEmpty()) {
				checkStatusCheckSets(
						diffs,
						"ruleset." + rName + ".required_status_checks",
						wantChecks,
						gotChecks
				);
			}

			// Check required reviews
			if (wantedRuleset.requiredReviewCount() != null) {
				Integer gotCount = null;
				if (actualRulesByType.get(
						RulesetRuleType.PULL_REQUEST
				) instanceof Rule.PullRequest pr && pr.parameters() != null) {
					gotCount = pr.parameters().requiredApprovingReviewCount();
				}
				check(
						diffs,
						"ruleset." + rName + ".required_review_count",
						wantedRuleset.requiredReviewCount(),
						gotCount
				);
			}

			// Check required code scanning
			Rule.CodeScanning csRule = actualRulesByType.get(
					RulesetRuleType.CODE_SCANNING
			) instanceof Rule.CodeScanning cs ? cs : null;
			List<Rule.CodeScanningTool> actualTools = csRule != null
					&& csRule.parameters() != null
					&& csRule.parameters().codeScanningTools() != null
							? csRule.parameters().codeScanningTools()
							: List.of();
			if (!wantedRuleset.requiredCodeScanning().isEmpty()
					|| !actualTools.isEmpty()) {
				Set<String> wantTools = wantedRuleset.requiredCodeScanning()
						.stream()
						.map(cst -> cst.tool())
						.collect(Collectors.toSet());
				Set<String> gotTools = new HashSet<>();
				for (var tool : actualTools) {
					gotTools.add(tool.tool());
				}
				checkSets(
						diffs,
						"ruleset." + rName + ".required_code_scanning",
						wantTools,
						gotTools
				);
			}

			// Check creation
			check(
					diffs,
					"ruleset." + rName + ".creation",
					wantedRuleset.creation(),
					actualRulesByType.containsKey(RulesetRuleType.CREATION)
			);

			// Check deletion
			check(
					diffs,
					"ruleset." + rName + ".deletion",
					wantedRuleset.deletion(),
					actualRulesByType.containsKey(RulesetRuleType.DELETION)
			);

			// Check required_signatures
			check(
					diffs,
					"ruleset." + rName + ".required_signatures",
					wantedRuleset.requiredSignatures(),
					actualRulesByType
							.containsKey(RulesetRuleType.REQUIRED_SIGNATURES)
			);

			// Check update
			check(
					diffs,
					"ruleset." + rName + ".update",
					wantedRuleset.update(),
					actualRulesByType.containsKey(RulesetRuleType.UPDATE)
			);
			if (wantedRuleset.update() && actualRulesByType.get(
					RulesetRuleType.UPDATE
			) instanceof Rule.Update updateRule) {
				Boolean gotAllowsFetch = updateRule.parameters() != null
						? updateRule.parameters().updateAllowsFetchAndMerge()
						: null;
				check(
						diffs,
						"ruleset." + rName + ".update_allows_fetch_and_merge",
						wantedRuleset.updateAllowsFetchAndMerge(),
						Boolean.TRUE.equals(gotAllowsFetch)
				);
			}

			// Check pattern rules
			checkPatternRule(
					diffs,
					"ruleset." + rName + ".commit_message_pattern",
					wantedRuleset.commitMessagePattern(),
					actualRulesByType.get(
							RulesetRuleType.COMMIT_MESSAGE_PATTERN
					) instanceof Rule.CommitMessagePattern r ? r.parameters()
							: null
			);
			checkPatternRule(
					diffs,
					"ruleset." + rName + ".commit_author_email_pattern",
					wantedRuleset.commitAuthorEmailPattern(),
					actualRulesByType.get(
							RulesetRuleType.COMMIT_AUTHOR_EMAIL_PATTERN
					) instanceof Rule.CommitAuthorEmailPattern r
							? r.parameters()
							: null
			);
			checkPatternRule(
					diffs,
					"ruleset." + rName + ".committer_email_pattern",
					wantedRuleset.committerEmailPattern(),
					actualRulesByType.get(
							RulesetRuleType.COMMITTER_EMAIL_PATTERN
					) instanceof Rule.CommitterEmailPattern r ? r.parameters()
							: null
			);
			checkPatternRule(
					diffs,
					"ruleset." + rName + ".branch_name_pattern",
					wantedRuleset.branchNamePattern(),
					actualRulesByType.get(
							RulesetRuleType.BRANCH_NAME_PATTERN
					) instanceof Rule.BranchNamePattern r ? r.parameters()
							: null
			);
			checkPatternRule(
					diffs,
					"ruleset." + rName + ".tag_name_pattern",
					wantedRuleset.tagNamePattern(),
					actualRulesByType.get(
							RulesetRuleType.TAG_NAME_PATTERN
					) instanceof Rule.TagNamePattern r ? r.parameters() : null
			);

			// Check required_deployments
			Set<String> wantDeployments = wantedRuleset.requiredDeployments();
			Set<String> gotDeployments = new HashSet<>();
			if (actualRulesByType.get(
					RulesetRuleType.REQUIRED_DEPLOYMENTS
			) instanceof Rule.RequiredDeployments rd && rd.parameters() != null
					&& rd.parameters()
							.requiredDeploymentEnvironments() != null) {
				gotDeployments.addAll(
						rd.parameters().requiredDeploymentEnvironments()
				);
			}
			if (!wantDeployments.isEmpty() || !gotDeployments.isEmpty()) {
				checkSets(
						diffs,
						"ruleset." + rName + ".required_deployments",
						wantDeployments,
						gotDeployments
				);
			}

			// Check bypass actors (Feature 23)
			List<BypassActorArgs> wantBypass = wantedRuleset.bypassActors();
			if (!wantBypass.isEmpty()) {
				List<RulesetDetailsResponse.BypassActor> gotBypass = actualRuleset
						.bypassActors() != null ? actualRuleset.bypassActors()
								: List.of();
				Set<String> wantSet = wantBypass.stream()
						.map(
								a -> a.actorType() + ":" + a.actorId() + ":"
										+ a.bypassMode()
						)
						.collect(Collectors.toSet());
				Set<String> gotSet = gotBypass.stream()
						.map(
								a -> a.actorType() + ":" + a.actorId() + ":"
										+ a.bypassMode()
						)
						.collect(Collectors.toSet());
				checkSets(
						diffs,
						"ruleset." + rName + ".bypass_actors",
						wantSet,
						gotSet
				);
			}
		}

		// Check for extra rulesets (Feature 24)
		Set<String> desiredNames = desired.rulesets()
				.stream()
				.map(RulesetArgs::name)
				.collect(Collectors.toSet());
		for (RulesetDetailsResponse extra : actual.rulesets()) {
			if (!desiredNames.contains(extra.name())) {
				diffs.add("ruleset." + extra.name() + ": extra");
			}
		}
	}

	private void checkSecrets(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		checkSets(
				diffs,
				"action_secrets",
				new HashSet<>(desired.actionsSecrets()),
				new HashSet<>(actual.actionSecretNames())
		);

		// Environments: check names
		Set<String> wantEnvs = new LinkedHashSet<>(
				desired.environments().keySet()
		);
		if (desired.pages()) {
			wantEnvs.add("github-pages");
		}
		Set<String> gotEnvs = actual.environmentSecretNames().keySet();
		checkSets(diffs, "environments", wantEnvs, gotEnvs);

		// Environment secrets: for each desired env that exists, check secrets
		for (var entry : desired.environments().entrySet()) {
			String envName = entry.getKey();
			List<String> wantSecrets = entry.getValue().secrets();
			List<String> gotSecrets = actual.environmentSecretNames()
					.getOrDefault(envName, List.of());
			checkSets(
					diffs,
					"environment." + envName + ".secrets",
					new HashSet<>(wantSecrets),
					new HashSet<>(gotSecrets)
			);
		}
	}

	private void checkEnvironmentConfig(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		for (var entry : desired.environments().entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();
			EnvironmentDetailsResponse actualEnv = actual.environmentDetails()
					.get(envName);
			if (actualEnv == null) {
				continue; // already flagged as missing by checkSecrets
			}

			if (wantEnv.waitTimer() != null) {
				check(
						diffs,
						"environment." + envName + ".wait_timer",
						wantEnv.waitTimer(),
						actualEnv.getWaitTimer()
				);
			}
			if (wantEnv.deploymentBranchPolicy() != null) {
				var want = wantEnv.deploymentBranchPolicy();
				var got = actualEnv.deploymentBranchPolicy();
				boolean gotProtected = got != null && got.protectedBranches();
				boolean gotCustom = got != null && got.customBranchPolicies();
				check(
						diffs,
						"environment." + envName
								+ ".deployment_branch_policy.protected_branches",
						want.protectedBranches(),
						gotProtected
				);
				check(
						diffs,
						"environment." + envName
								+ ".deployment_branch_policy.custom_branch_policies",
						want.customBranchPolicies(),
						gotCustom
				);
			}
			if (!wantEnv.reviewers().isEmpty()) {
				Set<String> want = wantEnv.reviewers()
						.stream()
						.map(r -> r.type().name() + ":" + r.id())
						.collect(Collectors.toSet());
				checkSets(
						diffs,
						"environment." + envName + ".reviewers",
						want,
						actualEnv.getReviewerIds()
				);
			}
		}
	}

	private void checkPages(
			List<String> diffs,
			RepositoryState actual,
			RepositoryArgs desired
	) {
		if (!desired.pages()) {
			return;
		}

		Optional<PagesResponse> actualPages = actual.pages();
		if (actualPages.isEmpty()) {
			diffs.add("pages: missing");
			return;
		}
		PagesResponse p = actualPages.orElseThrow();
		PagesArgs want = desired.pagesArgs();

		check(
				diffs,
				"pages.build_type",
				want.buildType().name().toLowerCase(Locale.ROOT),
				p.buildType() != null
						? p.buildType().name().toLowerCase(Locale.ROOT)
						: null
		);

		if (want.buildType() == PagesResponse.BuildType.LEGACY
				&& p.source() != null) {
			check(
					diffs,
					"pages.source.branch",
					want.sourceBranch(),
					p.source().branch()
			);
			check(
					diffs,
					"pages.source.path",
					want.sourcePath(),
					p.source().path()
			);
		}

		check(diffs, "pages.https_enforced", true, p.httpsEnforced());
	}

	// ─── Fix
	// ──────────────────────────────────────────────────────────────

	List<String> applyFixes(
			String name,
			RepositoryState actual,
			RepositoryArgs desired,
			List<String> diffs
	) throws IOException, InterruptedException {
		List<String> remaining = new ArrayList<>(diffs);

		// Special case: archive the repo
		if (desired.archived()) {
			if (remaining.remove("archived")) {
				client.updateRepository(
						org,
						name,
						RepositoryUpdateRequest.builder().archived(true).build()
				);
				System.out.printf("[FIXED]   %s: archived%n", name);
			}
			return remaining;
		}

		// Repo settings group (fixable)
		List<String> repoSettingsDiffs = new ArrayList<>();
		checkRepoSettings(repoSettingsDiffs, actual, desired);
		if (!repoSettingsDiffs.isEmpty()) {
			var request = RepositoryUpdateRequest.builder()
					.archived(desired.archived())
					.description(desired.description())
					.homepage(desired.homepageUrl())
					.hasIssues(desired.hasIssues())
					.hasProjects(desired.hasProjects())
					.hasWiki(desired.hasWiki())
					.hasDiscussions(desired.hasDiscussions())
					.isTemplate(desired.isTemplate())
					.allowForking(desired.allowForking())
					.webCommitSignoffRequired(
							desired.webCommitSignoffRequired()
					)
					.allowMergeCommit(desired.allowMergeCommit())
					.allowSquashMerge(desired.allowSquashMerge())
					.allowRebaseMerge(desired.allowRebaseMerge())
					.allowUpdateBranch(desired.allowUpdateBranch())
					.allowAutoMerge(desired.allowAutoMerge())
					.deleteBranchOnMerge(desired.deleteBranchOnMerge())
					.squashMergeCommitTitle(desired.squashMergeCommitTitle())
					.squashMergeCommitMessage(
							desired.squashMergeCommitMessage()
					)
					.mergeCommitTitle(desired.mergeCommitTitle())
					.mergeCommitMessage(desired.mergeCommitMessage())
					.defaultBranch(desired.defaultBranch())
					.build();
			client.updateRepository(org, name, request);
			remaining.removeAll(repoSettingsDiffs);
			for (String diff : repoSettingsDiffs) {
				System.out.printf("[FIXED]   %s: %s updated%n", name, diff);
			}
		}

		// Topics group (fixable)
		List<String> topicsDiffs = new ArrayList<>();
		checkTopics(topicsDiffs, actual, desired);
		if (!topicsDiffs.isEmpty()) {
			client.replaceTopics(org, name, desired.topics());
			remaining.removeAll(topicsDiffs);
			System.out.printf("[FIXED]   %s: topics updated%n", name);
		}

		// Security settings group (fixable)
		List<String> securityDiffs = new ArrayList<>();
		checkSecuritySettings(securityDiffs, actual, desired);
		if (!securityDiffs.isEmpty()) {
			if (securityDiffs.stream()
					.anyMatch(d -> d.startsWith("vulnerability_alerts"))) {
				if (desired.vulnerabilityAlerts()) {
					client.enableVulnerabilityAlerts(org, name);
					System.out.printf(
							"[FIXED]   %s: vulnerability_alerts enabled%n",
							name
					);
				} else {
					client.disableVulnerabilityAlerts(org, name);
					System.out.printf(
							"[FIXED]   %s: vulnerability_alerts disabled%n",
							name
					);
				}
			}
			if (securityDiffs.stream()
					.anyMatch(d -> d.startsWith("automated_security_fixes"))) {
				if (desired.automatedSecurityFixes()) {
					client.enableAutomatedSecurityFixes(org, name);
					System.out.printf(
							"[FIXED]   %s: automated_security_fixes enabled%n",
							name
					);
				} else {
					client.disableAutomatedSecurityFixes(org, name);
					System.out.printf(
							"[FIXED]   %s: automated_security_fixes disabled%n",
							name
					);
				}
			}
			if (securityDiffs.stream()
					.anyMatch(
							d -> d.contains("immutable_releases")
									&& d.contains("want=true")
					)) {
				client.enableImmutableReleases(org, name);
				System.out.printf(
						"[FIXED]   %s: immutable_releases enabled%n",
						name
				);
			}
			if (securityDiffs.stream()
					.anyMatch(
							d -> d.contains("immutable_releases")
									&& d.contains("want=false")
					)) {
				client.disableImmutableReleases(org, name);
				System.out.printf(
						"[FIXED]   %s: immutable_releases disabled%n",
						name
				);
			}
			boolean ssDrifted = securityDiffs.stream()
					.anyMatch(d -> d.startsWith("secret_scanning:"));
			boolean sspDrifted = securityDiffs.stream()
					.anyMatch(
							d -> d.startsWith(
									"secret_scanning_push_protection:"
							)
					);
			boolean ssvDrifted = securityDiffs.stream()
					.anyMatch(
							d -> d.startsWith(
									"secret_scanning_validity_checks:"
							)
					);
			boolean ssnpDrifted = securityDiffs.stream()
					.anyMatch(
							d -> d.startsWith(
									"secret_scanning_non_provider_patterns:"
							)
					);
			if (ssDrifted || sspDrifted || ssvDrifted || ssnpDrifted) {
				var saBuilder = SecurityAndAnalysis.builder();
				if (ssDrifted) {
					saBuilder.secretScanning(desired.secretScanning());
				}
				if (sspDrifted) {
					saBuilder.secretScanningPushProtection(
							desired.secretScanningPushProtection()
					);
				}
				if (ssvDrifted) {
					saBuilder.secretScanningValidityChecks(
							desired.secretScanningValidityChecks()
					);
				}
				if (ssnpDrifted) {
					saBuilder.secretScanningNonProviderPatterns(
							desired.secretScanningNonProviderPatterns()
					);
				}
				var sa = saBuilder.build();
				client.updateRepository(
						org,
						name,
						RepositoryUpdateRequest.builder()
								.securityAndAnalysis(sa)
								.build()
				);
				System.out.printf(
						"[FIXED]   %s: secret_scanning settings updated%n",
						name
				);
			}
			if (securityDiffs.stream()
					.anyMatch(
							d -> d.startsWith("private_vulnerability_reporting")
					)) {
				if (desired.privateVulnerabilityReporting()) {
					client.enablePrivateVulnerabilityReporting(org, name);
					System.out.printf(
							"[FIXED]   %s: private_vulnerability_reporting enabled%n",
							name
					);
				} else {
					client.disablePrivateVulnerabilityReporting(org, name);
					System.out.printf(
							"[FIXED]   %s: private_vulnerability_reporting disabled%n",
							name
					);
				}
			}
			if (securityDiffs.stream()
					.anyMatch(
							d -> d.startsWith("code_scanning_default_setup")
					)) {
				if (desired.codeScanningDefaultSetup()) {
					client.enableCodeScanningDefaultSetup(org, name);
					System.out.printf(
							"[FIXED]   %s: code_scanning_default_setup enabled%n",
							name
					);
				} else {
					client.disableCodeScanningDefaultSetup(org, name);
					System.out.printf(
							"[FIXED]   %s: code_scanning_default_setup disabled%n",
							name
					);
				}
			}
			remaining.removeAll(securityDiffs);
		}

		// Workflow permissions (fixable)
		List<String> workflowDiffs = new ArrayList<>();
		checkWorkflowPermissions(workflowDiffs, actual, desired);
		if (!workflowDiffs.isEmpty()) {
			client.updateWorkflowPermissions(
					org,
					name,
					new WorkflowPermissions(
							desired.defaultWorkflowPermissions(),
							desired.canApprovePullRequestReviews()
					)
			);
			remaining.removeAll(workflowDiffs);
			System.out.printf(
					"[FIXED]   %s: workflow_permissions updated%n",
					name
			);
		}

		// Branch protection (fixable for public repos)
		List<String> branchProtectionDiffs = new ArrayList<>();
		checkBranchProtection(branchProtectionDiffs, actual, desired);
		if (!branchProtectionDiffs.isEmpty()) {
			for (BranchProtectionArgs wantedBp : desired.branchProtections()
					.values()) {
				boolean hasBpDrift = branchProtectionDiffs.stream()
						.anyMatch(d -> d.contains("." + wantedBp.pattern()));
				if (!hasBpDrift) {
					continue;
				}

				Set<StatusCheckArgs> wantedStatusChecks = wantedBp
						.requiredStatusChecks();
				List<BranchProtectionRequest.RequiredStatusChecks.StatusCheck> checks = wantedStatusChecks
						.stream()
						.map(
								sc -> new BranchProtectionRequest.RequiredStatusChecks.StatusCheck(
										sc.getContext(),
										sc.getAppId()
								)
						)
						.toList();

				BranchProtectionRequest.RequiredPullRequestReviews rpr = null;
				BranchProtectionRequest.Restrictions restrictions = null;

				boolean hasPrReviews = wantedBp.dismissStaleReviews()
						|| wantedBp.requireCodeOwnerReviews()
						|| wantedBp.requiredApprovingReviewCount() != null
						|| wantedBp.requireLastPushApproval() != null;
				if (hasPrReviews) {
					rpr = new BranchProtectionRequest.RequiredPullRequestReviews(
							wantedBp.dismissStaleReviews(),
							wantedBp.requireCodeOwnerReviews(),
							wantedBp.requiredApprovingReviewCount(),
							wantedBp.requireLastPushApproval()
					);
				}

				if (!wantedBp.users().isEmpty() || !wantedBp.teams().isEmpty()
						|| !wantedBp.apps().isEmpty()) {
					restrictions = new BranchProtectionRequest.Restrictions(
							wantedBp.users(),
							wantedBp.teams(),
							wantedBp.apps()
					);
				}

				var payload = new BranchProtectionRequest(
						new BranchProtectionRequest.RequiredStatusChecks(
								false,
								checks
						),
						wantedBp.enforceAdmins(),
						rpr,
						restrictions,
						wantedBp.requiredLinearHistory(),
						wantedBp.allowForcePushes()
				);
				client.updateBranchProtection(
						org,
						name,
						wantedBp.pattern(),
						payload
				);
				System.out.printf(
						"[FIXED]   %s: branch_protection:%s updated%n",
						name,
						wantedBp.pattern()
				);
			}
			remaining.removeAll(branchProtectionDiffs);
		}

		// Rulesets (fixable)
		List<String> rulesetDiffs = new ArrayList<>();
		checkRulesets(rulesetDiffs, actual, desired);
		if (!rulesetDiffs.isEmpty()) {
			Map<String, RulesetDetailsResponse> actualByName = actual.rulesets()
					.stream()
					.collect(
							Collectors.toMap(
									RulesetDetailsResponse::name,
									r -> r,
									(a, b) -> a
							)
					);
			for (RulesetArgs wantedRuleset : desired.rulesets()) {
				String prefix = "ruleset." + wantedRuleset.name();
				boolean hasDrift = rulesetDiffs.stream()
						.anyMatch(d -> d.startsWith(prefix));
				if (!hasDrift) {
					continue;
				}
				RulesetRequest payload = buildRulesetRequest(wantedRuleset);
				RulesetDetailsResponse existing = actualByName
						.get(wantedRuleset.name());
				if (existing == null) {
					client.createRuleset(org, name, payload);
					System.out.printf(
							"[FIXED]   %s: ruleset.%s created%n",
							name,
							wantedRuleset.name()
					);
				} else {
					client.updateRuleset(org, name, existing.id(), payload);
					System.out.printf(
							"[FIXED]   %s: ruleset.%s updated%n",
							name,
							wantedRuleset.name()
					);
				}
			}
			// Delete extra rulesets (Feature 24)
			Set<String> desiredNames = desired.rulesets()
					.stream()
					.map(RulesetArgs::name)
					.collect(Collectors.toSet());
			for (RulesetDetailsResponse extra : actual.rulesets()) {
				if (!desiredNames.contains(extra.name()) && rulesetDiffs
						.contains("ruleset." + extra.name() + ": extra")) {
					client.deleteRuleset(org, name, extra.id());
					System.out.printf(
							"[FIXED]   %s: ruleset.%s deleted%n",
							name,
							extra.name()
					);
				}
			}
			remaining.removeAll(rulesetDiffs);
		}

		// Pages (fixable)
		List<String> pagesDiffs = new ArrayList<>();
		checkPages(pagesDiffs, actual, desired);
		if (!pagesDiffs.isEmpty()) {
			if (actual.pages().isEmpty()) {
				client.createPages(
						org,
						name,
						buildPagesCreateRequest(desired.pagesArgs())
				);
				System.out.printf("[FIXED]   %s: pages created%n", name);
			} else {
				client.updatePages(
						org,
						name,
						buildPagesUpdateRequest(desired.pagesArgs())
				);
				System.out.printf("[FIXED]   %s: pages updated%n", name);
			}
			remaining.removeAll(pagesDiffs);
		}

		// Environment config (wait_timer, deployment_branch_policy, reviewers)
		// — fixable
		List<String> envConfigDiffs = new ArrayList<>();
		checkEnvironmentConfig(envConfigDiffs, actual, desired);
		if (!envConfigDiffs.isEmpty()) {
			for (var entry : desired.environments().entrySet()) {
				String envName = entry.getKey();
				String prefix = "environment." + envName + ".";
				boolean hasDrift = envConfigDiffs.stream()
						.anyMatch(d -> d.startsWith(prefix));
				if (!hasDrift) {
					continue;
				}
				EnvironmentUpdateRequest payload = buildEnvironmentUpdateRequest(
						entry.getValue()
				);
				client.updateEnvironment(org, name, envName, payload);
				System.out.printf(
						"[FIXED]   %s: environment.%s updated%n",
						name,
						envName
				);
			}
			remaining.removeAll(envConfigDiffs);
		}

		// Action secrets and environment secrets — fix missing ones if values
		// are available in the githubSecrets map
		List<String> secretDiffs = new ArrayList<>();
		checkSecrets(secretDiffs, actual, desired);
		if (!secretDiffs.isEmpty()) {
			// --- Action secrets ---
			Set<String> missingActionSecrets = new HashSet<>(
					desired.actionsSecrets()
			);
			missingActionSecrets
					.removeAll(new HashSet<>(actual.actionSecretNames()));
			if (!missingActionSecrets.isEmpty()) {
				boolean allFixed = true;
				SecretPublicKeyResponse publicKey = null;
				for (String secretName : missingActionSecrets) {
					String mapKey = name + "-" + secretName;
					String value = githubSecrets.get(mapKey);
					if (value == null) {
						allFixed = false;
						continue;
					}
					if (publicKey == null) {
						publicKey = client.getActionSecretPublicKey(org, name);
					}
					String encrypted = encryptSecret(publicKey.key(), value);
					client.createOrUpdateActionSecret(
							org,
							name,
							secretName,
							new SecretRequest(encrypted, publicKey.keyId())
					);
					System.out.printf(
							"[FIXED]   %s: action secret %s created%n",
							name,
							secretName
					);
				}
				if (allFixed) {
					secretDiffs.stream()
							.filter(
									d -> d.startsWith("action_secrets missing:")
							)
							.forEach(remaining::remove);
				}
			}

			// --- Environment secrets ---
			for (var entry : desired.environments().entrySet()) {
				String envName = entry.getKey();
				List<String> wantSecrets = entry.getValue().secrets();
				Set<String> missingEnvSecrets = new HashSet<>(wantSecrets);
				missingEnvSecrets.removeAll(
						new HashSet<>(
								actual.environmentSecretNames()
										.getOrDefault(envName, List.of())
						)
				);
				if (missingEnvSecrets.isEmpty()) {
					continue;
				}
				boolean allFixed = true;
				SecretPublicKeyResponse publicKey = null;
				for (String secretName : missingEnvSecrets) {
					String mapKey = name + "-" + envName + "-" + secretName;
					String value = githubSecrets.get(mapKey);
					if (value == null) {
						allFixed = false;
						continue;
					}
					if (publicKey == null) {
						publicKey = client.getEnvironmentSecretPublicKey(
								org,
								name,
								envName
						);
					}
					String encrypted = encryptSecret(publicKey.key(), value);
					client.createOrUpdateEnvironmentSecret(
							org,
							name,
							envName,
							secretName,
							new SecretRequest(encrypted, publicKey.keyId())
					);
					System.out.printf(
							"[FIXED]   %s: environment.%s secret %s created%n",
							name,
							envName,
							secretName
					);
				}
				if (allFixed) {
					String prefix = "environment." + envName
							+ ".secrets missing:";
					secretDiffs.stream()
							.filter(d -> d.startsWith(prefix))
							.forEach(remaining::remove);
				}
			}
		}

		return remaining;
	}

	private static PagesCreateRequest buildPagesCreateRequest(PagesArgs args) {
		PagesCreateRequest.Source source = null;
		if (args.buildType() == PagesResponse.BuildType.LEGACY) {
			source = new PagesCreateRequest.Source(
					args.sourceBranch(),
					args.sourcePath()
			);
		}
		return new PagesCreateRequest(
				args.buildType().name().toLowerCase(Locale.ROOT),
				source
		);
	}

	private static PagesUpdateRequest buildPagesUpdateRequest(PagesArgs args) {
		PagesUpdateRequest.Source source = null;
		if (args.buildType() == PagesResponse.BuildType.LEGACY) {
			source = new PagesUpdateRequest.Source(
					args.sourceBranch(),
					args.sourcePath()
			);
		}
		return new PagesUpdateRequest(
				args.buildType().name().toLowerCase(Locale.ROOT),
				source,
				true
		);
	}

	private static EnvironmentUpdateRequest buildEnvironmentUpdateRequest(
			EnvironmentArgs args
	) {
		List<EnvironmentUpdateRequest.Reviewer> reviewers = args.reviewers()
				.stream()
				.map(
						r -> new EnvironmentUpdateRequest.Reviewer(
								r.type(),
								r.id()
						)
				)
				.toList();
		EnvironmentUpdateRequest.DeploymentBranchPolicy dbp = null;
		if (args.deploymentBranchPolicy() != null) {
			dbp = new EnvironmentUpdateRequest.DeploymentBranchPolicy(
					args.deploymentBranchPolicy().protectedBranches(),
					args.deploymentBranchPolicy().customBranchPolicies()
			);
		}
		return new EnvironmentUpdateRequest(
				args.waitTimer(),
				reviewers.isEmpty() ? null : reviewers,
				dbp
		);
	}

	private static RulesetRequest buildRulesetRequest(RulesetArgs args) {
		List<Rule> rules = new ArrayList<>();
		if (args.creation()) {
			rules.add(new Rule.Creation());
		}
		if (args.deletion()) {
			rules.add(new Rule.Deletion());
		}
		if (args.requiredSignatures()) {
			rules.add(new Rule.RequiredSignatures());
		}
		if (args.requiredLinearHistory()) {
			rules.add(new Rule.RequiredLinearHistory());
		}
		if (args.noForcePushes()) {
			rules.add(new Rule.NonFastForward());
		}
		if (args.update()) {
			rules.add(
					new Rule.Update(
							new Rule.Update.Parameters(
									args.updateAllowsFetchAndMerge()
							)
					)
			);
		}
		if (!args.requiredStatusChecks().isEmpty()) {
			List<Rule.StatusCheck> checks = args.requiredStatusChecks()
					.stream()
					.map(
							statusCheckArgs -> new Rule.StatusCheck(
									statusCheckArgs.getContext(),
									statusCheckArgs.getAppId()
							)
					)
					.toList();
			rules.add(
					new Rule.RequiredStatusChecks(
							new Rule.RequiredStatusChecks.Parameters(
									checks,
									false
							)
					)
			);
		}
		if (args.requiredReviewCount() != null) {
			rules.add(
					new Rule.PullRequest(
							new Rule.PullRequest.Parameters(
									args.requiredReviewCount(),
									false,
									false,
									false
							)
					)
			);
		}
		if (!args.requiredCodeScanning().isEmpty()) {
			List<Rule.CodeScanningTool> tools = args.requiredCodeScanning()
					.stream()
					.map(
							csTool -> new Rule.CodeScanningTool(
									csTool.tool(),
									csTool.alertsThreshold()
											.name()
											.toLowerCase(Locale.ROOT),
									csTool.securityAlertsThreshold()
											.name()
											.toLowerCase(Locale.ROOT)
							)
					)
					.toList();
			rules.add(
					new Rule.CodeScanning(
							new Rule.CodeScanning.Parameters(tools)
					)
			);
		}
		if (args.commitMessagePattern() != null) {
			rules.add(
					new Rule.CommitMessagePattern(
							toPatternParameters(args.commitMessagePattern())
					)
			);
		}
		if (args.commitAuthorEmailPattern() != null) {
			rules.add(
					new Rule.CommitAuthorEmailPattern(
							toPatternParameters(args.commitAuthorEmailPattern())
					)
			);
		}
		if (args.committerEmailPattern() != null) {
			rules.add(
					new Rule.CommitterEmailPattern(
							toPatternParameters(args.committerEmailPattern())
					)
			);
		}
		if (args.branchNamePattern() != null) {
			rules.add(
					new Rule.BranchNamePattern(
							toPatternParameters(args.branchNamePattern())
					)
			);
		}
		if (args.tagNamePattern() != null) {
			rules.add(
					new Rule.TagNamePattern(
							toPatternParameters(args.tagNamePattern())
					)
			);
		}
		if (!args.requiredDeployments().isEmpty()) {
			rules.add(
					new Rule.RequiredDeployments(
							new Rule.RequiredDeployments.Parameters(
									List.copyOf(args.requiredDeployments())
							)
					)
			);
		}
		List<RulesetRequest.BypassActorRequest> bypassActors = args
				.bypassActors()
				.stream()
				.map(
						a -> new RulesetRequest.BypassActorRequest(
								a.actorId(),
								a.actorType(),
								a.bypassMode()
						)
				)
				.toList();
		var refName = new RulesetRequest.Conditions.RefName(
				args.includePatterns(),
				List.of()
		);
		var conditions = new RulesetRequest.Conditions(
				refName,
				null,
				null,
				null
		);
		return new RulesetRequest(
				args.name(),
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				bypassActors,
				conditions,
				rules
		);
	}

	private static Rule.PatternParameters toPatternParameters(
			RulePatternArgs args
	) {
		return new Rule.PatternParameters(
				args.name(),
				args.negate(),
				args.operator(),
				args.pattern()
		);
	}

	// ─── Report
	// ──────────────────────────────────────────────────────────────

	public void printReport(CheckResult result) {
		List<CheckResult.RepoCheckResult> sorted = result.repos()
				.stream()
				.sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
				.toList();

		for (CheckResult.RepoCheckResult r : sorted) {
			switch (r.status()) {
			case OK -> System.out.printf("[OK]      %s%n", r.name());
			case DRIFT -> {
				System.out.printf("[DRIFT]   %s:%n", r.name());
				r.diffs()
						.forEach(d -> System.out.printf("            %s%n", d));
			}
			case ERROR ->
				System.out.printf("[ERROR]   %s: %s%n", r.name(), r.error());
			case UNKNOWN -> System.out
					.printf("[UNKNOWN] %s: not in desired config%n", r.name());
			case MISSING -> System.out.printf(
					"[MISSING] %s: in config but not found in org%n",
					r.name()
			);
			}
		}

		System.out.println();
		System.out.println("=== Summary ===");
		System.out.printf("Repos checked:  %d%n", result.repos().size());
		System.out.printf("OK:             %d%n", result.okCount());
		System.out.printf("Drifted:        %d%n", result.driftCount());
		System.out.printf("Errored:        %d%n", result.errorCount());
		System.out.printf("Unknown:        %d%n", result.unknownCount());
	}

	private static String encryptSecret(
			String base64PublicKey,
			String plaintext
	) {
		var sodium = new LazySodiumJava(new SodiumJava());
		byte[] decodedKey = Base64.getDecoder().decode(base64PublicKey);
		byte[] msgBytes = plaintext.getBytes(StandardCharsets.UTF_8);
		byte[] cipherText = new byte[msgBytes.length + Box.SEALBYTES];
		sodium.cryptoBoxSeal(cipherText, msgBytes, msgBytes.length, decodedKey);
		return Base64.getEncoder().encodeToString(cipherText);
	}

	// ─── Helpers
	// ──────────────────────────────────────────────────────────────

	private static void check(
			List<String> diffs,
			String field,
			Object want,
			Object got
	) {
		if (!Objects.equals(want, got)) {
			diffs.add(field + ": want=" + want + " got=" + got);
		}
	}

	private static void checkPatternRule(
			List<String> diffs,
			String field,
			RulePatternArgs want,
			Rule.PatternParameters got
	) {
		if (want == null) {
			return;
		}
		if (got == null) {
			diffs.add(field + ": missing");
			return;
		}
		check(
				diffs,
				field + ".negate",
				want.negate(),
				Boolean.TRUE.equals(got.negate())
		);
		check(diffs, field + ".operator", want.operator(), got.operator());
		check(diffs, field + ".pattern", want.pattern(), got.pattern());
	}

	private static void checkStatusCheckSets(
			List<String> diffs,
			String field,
			Set<StatusCheckArgs> want,
			Set<StatusCheckArgs> got
	) {
		Set<StatusCheckArgs> missing = new HashSet<>(want);
		missing.removeAll(got);
		Set<StatusCheckArgs> extra = new HashSet<>(got);
		extra.removeAll(want);
		if (!missing.isEmpty()) {
			diffs.add(field + " missing: " + sortedStatusCheckArgs(missing));
		}
		if (!extra.isEmpty()) {
			diffs.add(field + " extra: " + sortedStatusCheckArgs(extra));
		}
	}

	private static String sortedStatusCheckArgs(Set<StatusCheckArgs> s) {
		List<String> list = new ArrayList<>(s.stream().map(sc -> {
			Integer appId = sc.getAppId();
			return appId != null ? sc.getContext() + " (appId=" + appId + ")"
					: sc.getContext();
		}).toList());
		Collections.sort(list);
		return list.toString();
	}

	private static void checkSets(
			List<String> diffs,
			String field,
			Set<String> want,
			Set<String> got
	) {
		Set<String> missing = new HashSet<>(want);
		missing.removeAll(got);
		Set<String> extra = new HashSet<>(got);
		extra.removeAll(want);
		if (!missing.isEmpty()) {
			diffs.add(field + " missing: " + sorted(missing));
		}
		if (!extra.isEmpty()) {
			diffs.add(field + " extra: " + sorted(extra));
		}
	}

	private static List<String> sorted(Set<String> s) {
		List<String> list = new ArrayList<>(s);
		Collections.sort(list);
		return list;
	}

}
