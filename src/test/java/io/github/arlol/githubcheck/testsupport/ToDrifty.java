package io.github.arlol.githubcheck.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Test-only conversion from the hand-written {@code *Args} builders to the
 * Pkl-generated {@code Drifty.*} types the production code consumes. Inverse
 * enum conversions are uniformly {@code Drifty.X.valueOf(clientEnum.name())}
 * because the Pkl-generated enums share their constant names with the GitHub
 * client enums.
 */
public final class ToDrifty {

	private ToDrifty() {
	}

	public static List<Drifty.Repository> repositories(
			List<RepositoryArgs> repos
	) {
		return repos.stream().map(ToDrifty::repository).toList();
	}

	public static Drifty.Repository repository(RepositoryArgs r) {
		Map<String, Drifty.Ruleset> rulesets = new LinkedHashMap<>();
		for (RulesetArgs ruleset : r.rulesets()) {
			rulesets.put(ruleset.name(), ruleset(ruleset));
		}
		Map<String, Drifty.BranchProtection> branchProtections = new LinkedHashMap<>();
		for (var e : r.branchProtections().entrySet()) {
			branchProtections.put(e.getKey(), branchProtection(e.getValue()));
		}
		Map<String, Drifty.Environment> environments = new LinkedHashMap<>();
		for (var e : r.environments().entrySet()) {
			environments.put(e.getKey(), environment(e.getValue()));
		}
		return new Drifty.Repository(
				r.owner(),
				r.name(),
				r.archived(),
				r.description(),
				r.homepageUrl(),
				Drifty.Visibility.valueOf(r.visibility().name()),
				List.copyOf(r.topics()),
				r.defaultBranch(),
				r.hasIssues(),
				r.hasProjects(),
				r.hasWiki(),
				r.hasDiscussions(),
				r.isTemplate(),
				r.allowForking(),
				r.webCommitSignoffRequired(),
				r.allowMergeCommit(),
				r.allowSquashMerge(),
				r.allowRebaseMerge(),
				r.allowAutoMerge(),
				r.deleteBranchOnMerge(),
				r.allowUpdateBranch(),
				Drifty.SquashMergeCommitTitle
						.valueOf(r.squashMergeCommitTitle().name()),
				Drifty.SquashMergeCommitMessage
						.valueOf(r.squashMergeCommitMessage().name()),
				Drifty.MergeCommitTitle.valueOf(r.mergeCommitTitle().name()),
				Drifty.MergeCommitMessage
						.valueOf(r.mergeCommitMessage().name()),
				r.vulnerabilityAlerts(),
				r.automatedSecurityFixes(),
				r.secretScanning(),
				r.secretScanningPushProtection(),
				r.secretScanningValidityChecks(),
				r.secretScanningNonProviderPatterns(),
				r.privateVulnerabilityReporting(),
				r.codeScanningDefaultSetup(),
				r.advancedSecurity(),
				r.secretScanningAiDetection(),
				r.secretScanningDelegatedAlertDismissal(),
				r.secretScanningDelegatedBypass(),
				r.secretScanningDelegatedBypassReviewers()
						.stream()
						.map(ToDrifty::bypassReviewer)
						.toList(),
				Drifty.WorkflowPermissions
						.valueOf(r.defaultWorkflowPermissions().name()),
				r.canApprovePullRequestReviews(),
				r.immutableReleases(),
				r.pagesArgs() != null ? pages(r.pagesArgs()) : null,
				new ArrayList<>(r.actionsSecrets()),
				branchProtections,
				rulesets,
				environments
		);
	}

	public static Drifty.Ruleset ruleset(RulesetArgs r) {
		return new Drifty.Ruleset(
				List.copyOf(r.includePatterns()),
				r.requiredLinearHistory(),
				r.noForcePushes(),
				r.requiredStatusChecks()
						.stream()
						.map(ToDrifty::statusCheck)
						.toList(),
				r.requiredReviewCount() != null
						? r.requiredReviewCount().longValue()
						: null,
				r.requiredCodeScanning()
						.stream()
						.map(ToDrifty::codeScanningTool)
						.toList(),
				r.creation(),
				r.deletion(),
				r.requiredSignatures(),
				r.update(),
				r.updateAllowsFetchAndMerge(),
				rulePattern(r.commitMessagePattern()),
				rulePattern(r.commitAuthorEmailPattern()),
				rulePattern(r.committerEmailPattern()),
				rulePattern(r.branchNamePattern()),
				rulePattern(r.tagNamePattern()),
				new ArrayList<>(r.requiredDeployments()),
				r.bypassActors().stream().map(ToDrifty::bypassActor).toList()
		);
	}

	public static Drifty.BranchProtection branchProtection(
			BranchProtectionArgs b
	) {
		return new Drifty.BranchProtection(
				b.enforceAdmins(),
				b.requiredLinearHistory(),
				b.allowForcePushes(),
				b.requireConversationResolution(),
				b.requiredStatusChecks()
						.stream()
						.map(ToDrifty::statusCheck)
						.toList(),
				b.requiredApprovingReviewCount() != null
						? b.requiredApprovingReviewCount().longValue()
						: null,
				b.dismissStaleReviews(),
				b.requireCodeOwnerReviews(),
				b.requireLastPushApproval(),
				List.copyOf(b.users()),
				List.copyOf(b.teams()),
				List.copyOf(b.apps())
		);
	}

	public static Drifty.Environment environment(EnvironmentArgs e) {
		var dbp = e.deploymentBranchPolicy();
		return new Drifty.Environment(
				new ArrayList<>(e.secrets()),
				e.waitTimer() != null ? e.waitTimer() : 0,
				dbp != null && dbp.protectedBranches(),
				dbp != null && dbp.customBranchPolicies()
		);
	}

	public static Drifty.Pages pages(PagesArgs p) {
		return new Drifty.Pages(
				p.buildType().name().toLowerCase(Locale.ROOT),
				p.sourceBranch(),
				p.sourcePath()
		);
	}

	public static Drifty.StatusCheck statusCheck(StatusCheckArgs s) {
		return new Drifty.StatusCheck(
				s.getContext(),
				s.getAppId() != null ? s.getAppId().longValue() : null
		);
	}

	public static Drifty.CodeScanningTool codeScanningTool(
			CodeScanningToolArgs c
	) {
		return new Drifty.CodeScanningTool(
				c.tool(),
				Drifty.AlertsThreshold.valueOf(c.alertsThreshold().name()),
				Drifty.SecurityAlertsThreshold
						.valueOf(c.securityAlertsThreshold().name())
		);
	}

	public static Drifty.RulePattern rulePattern(RulePatternArgs p) {
		if (p == null) {
			return null;
		}
		return new Drifty.RulePattern(
				p.name(),
				p.negate(),
				Drifty.PatternOperator.valueOf(p.operator().name()),
				p.pattern()
		);
	}

	public static Drifty.BypassActor bypassActor(BypassActorArgs a) {
		return new Drifty.BypassActor(
				a.actorId(),
				Drifty.ActorType.valueOf(a.actorType().name()),
				Drifty.BypassMode.valueOf(a.bypassMode().name())
		);
	}

	public static Drifty.SecretScanningBypassReviewer bypassReviewer(
			SecretScanningBypassReviewerArgs r
	) {
		return new Drifty.SecretScanningBypassReviewer(
				r.reviewerId(),
				Drifty.SecretScanningBypassReviewerType
						.valueOf(r.reviewerType().name())
		);
	}

}
