package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pkl.config.java.ConfigEvaluator;
import org.pkl.config.java.mapper.Types;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
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
import io.github.arlol.githubcheck.pkl.Drifty;

public final class PklConfigLoader {

	public static List<RepositoryArgs> load(Path pklFile) throws IOException {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			var root = evaluator.evaluate(ModuleSource.path(pklFile));
			List<Drifty.Repository> repos = root.get("repositories")
					.as(Types.listOf(Drifty.Repository.class));
			return repos.stream()
					.map(PklConfigLoader::mapRepository)
					.collect(Collectors.toList());
		}
	}

	private static RepositoryArgs mapRepository(Drifty.Repository r) {
		var builder = RepositoryArgs.create(r.owner, r.name)
				.archived(r.archived)
				.description(r.description)
				.homepageUrl(r.homepageUrl)
				.visibility(mapVisibility(r.visibility))
				.topics(r.topics.toArray(String[]::new))
				.defaultBranch(r.defaultBranch)
				.hasIssues(r.hasIssues)
				.hasProjects(r.hasProjects)
				.hasWiki(r.hasWiki)
				.allowMergeCommit(r.allowMergeCommit)
				.allowSquashMerge(r.allowSquashMerge)
				.allowRebaseMerge(r.allowRebaseMerge)
				.allowAutoMerge(r.allowAutoMerge)
				.deleteBranchOnMerge(r.deleteBranchOnMerge)
				.allowUpdateBranch(r.allowUpdateBranch)
				.vulnerabilityAlerts(r.vulnerabilityAlerts)
				.automatedSecurityFixes(r.automatedSecurityFixes)
				.secretScanning(r.secretScanning)
				.secretScanningPushProtection(r.secretScanningPushProtection)
				.secretScanningValidityChecks(r.secretScanningValidityChecks)
				.secretScanningNonProviderPatterns(
						r.secretScanningNonProviderPatterns
				)
				.privateVulnerabilityReporting(r.privateVulnerabilityReporting)
				.codeScanningDefaultSetup(r.codeScanningDefaultSetup)
				.defaultWorkflowPermissions(
						mapWorkflowPermissions(r.defaultWorkflowPermissions)
				)
				.canApprovePullRequestReviews(r.canApprovePullRequestReviews)
				.immutableReleases(r.immutableReleases)
				.actionsSecrets(r.actionsSecrets.toArray(String[]::new));

		if (r.pages != null) {
			builder.pages(mapPages(r.pages));
		}

		List<RulesetArgs> rulesets = r.rulesets.entrySet()
				.stream()
				.map(e -> mapRuleset(e.getKey(), e.getValue()))
				.toList();
		if (!rulesets.isEmpty()) {
			builder.rulesets(rulesets.toArray(RulesetArgs[]::new));
		}

		BranchProtectionArgs[] branchProtections = r.branchProtections
				.entrySet()
				.stream()
				.map(e -> mapBranchProtection(e.getKey(), e.getValue()))
				.toArray(BranchProtectionArgs[]::new);
		if (branchProtections.length > 0) {
			builder.branchProtections(branchProtections);
		}

		for (Map.Entry<String, Drifty.Environment> entry : r.environments
				.entrySet()) {
			builder.environment(
					entry.getKey(),
					mapEnvironment(entry.getKey(), entry.getValue())
			);
		}

		return builder.build();
	}

	private static RepositoryVisibility mapVisibility(Drifty.Visibility v) {
		return switch (v) {
		case PUBLIC -> RepositoryVisibility.PUBLIC;
		case PRIVATE -> RepositoryVisibility.PRIVATE;
		case INTERNAL -> RepositoryVisibility.INTERNAL;
		};
	}

	private static WorkflowPermissions.DefaultWorkflowPermissions mapWorkflowPermissions(
			Drifty.WorkflowPermissions v
	) {
		return switch (v) {
		case READ -> WorkflowPermissions.DefaultWorkflowPermissions.READ;
		case WRITE -> WorkflowPermissions.DefaultWorkflowPermissions.WRITE;
		};
	}

	private static PagesArgs mapPages(Drifty.Pages p) {
		if ("workflow".equals(p.buildType)) {
			return PagesArgs.workflow();
		}
		return PagesArgs.legacy(p.sourceBranch, p.sourcePath);
	}

	private static RulesetArgs mapRuleset(String name, Drifty.Ruleset r) {
		var builder = RulesetArgs.builder(name)
				.includePatterns(r.includePatterns.toArray(String[]::new))
				.requiredLinearHistory(r.requiredLinearHistory)
				.noForcePushes(r.noForcePushes)
				.creation(r.creation)
				.deletion(r.deletion)
				.requiredSignatures(r.requiredSignatures)
				.update(r.update)
				.updateAllowsFetchAndMerge(r.updateAllowsFetchAndMerge);

		if (!r.requiredStatusChecks.isEmpty()) {
			builder.requiredStatusChecks(
					r.requiredStatusChecks.stream()
							.map(PklConfigLoader::mapStatusCheck)
							.toArray(StatusCheckArgs[]::new)
			);
		}

		if (r.requiredReviewCount != null) {
			builder.requiredReviewCount(r.requiredReviewCount.intValue());
		}

		if (!r.requiredCodeScanning.isEmpty()) {
			builder.requiredCodeScanning(
					r.requiredCodeScanning.stream()
							.map(PklConfigLoader::mapCodeScanningTool)
							.toArray(CodeScanningToolArgs[]::new)
			);
		}

		if (r.commitMessagePattern != null) {
			builder.commitMessagePattern(
					mapRulePattern(r.commitMessagePattern)
			);
		}
		if (r.commitAuthorEmailPattern != null) {
			builder.commitAuthorEmailPattern(
					mapRulePattern(r.commitAuthorEmailPattern)
			);
		}
		if (r.committerEmailPattern != null) {
			builder.committerEmailPattern(
					mapRulePattern(r.committerEmailPattern)
			);
		}
		if (r.branchNamePattern != null) {
			builder.branchNamePattern(mapRulePattern(r.branchNamePattern));
		}
		if (r.tagNamePattern != null) {
			builder.tagNamePattern(mapRulePattern(r.tagNamePattern));
		}

		if (!r.requiredDeployments.isEmpty()) {
			builder.requiredDeployments(
					r.requiredDeployments.toArray(String[]::new)
			);
		}

		if (!r.bypassActors.isEmpty()) {
			builder.bypassActors(
					r.bypassActors.stream()
							.map(PklConfigLoader::mapBypassActor)
							.toArray(BypassActorArgs[]::new)
			);
		}

		return builder.build();
	}

	private static BranchProtectionArgs mapBranchProtection(
			String pattern,
			Drifty.BranchProtection bp
	) {
		var builder = BranchProtectionArgs.builder(pattern)
				.enforceAdmins(bp.enforceAdmins)
				.requiredLinearHistory(bp.requiredLinearHistory)
				.allowForcePushes(bp.allowForcePushes)
				.requireConversationResolution(bp.requireConversationResolution)
				.dismissStaleReviews(bp.dismissStaleReviews)
				.requireCodeOwnerReviews(bp.requireCodeOwnerReviews)
				.users(bp.users)
				.teams(bp.teams)
				.apps(bp.apps);

		if (!bp.requiredStatusChecks.isEmpty()) {
			Set<StatusCheckArgs> checks = bp.requiredStatusChecks.stream()
					.map(PklConfigLoader::mapStatusCheck)
					.collect(Collectors.toSet());
			builder.requiredStatusChecks(checks);
		}

		if (bp.requiredApprovingReviewCount != null) {
			builder.requiredApprovingReviewCount(
					bp.requiredApprovingReviewCount.intValue()
			);
		}

		if (bp.requireLastPushApproval != null) {
			builder.requireLastPushApproval(bp.requireLastPushApproval);
		}

		return builder.build();
	}

	private static StatusCheckArgs mapStatusCheck(Drifty.StatusCheck sc) {
		return StatusCheckArgs.builder()
				.context(sc.context)
				.appId((int) sc.appId)
				.build();
	}

	private static CodeScanningToolArgs mapCodeScanningTool(
			Drifty.CodeScanningTool t
	) {
		return CodeScanningToolArgs.builder()
				.tool(t.tool)
				.alertsThreshold(mapAlertsThreshold(t.alertsThreshold))
				.securityAlertsThreshold(
						mapSecurityAlertsThreshold(t.securityAlertsThreshold)
				)
				.build();
	}

	private static Rule.AlertsThreshold mapAlertsThreshold(
			Drifty.AlertsThreshold t
	) {
		return switch (t) {
		case NONE -> Rule.AlertsThreshold.NONE;
		case ERRORS -> Rule.AlertsThreshold.ERRORS;
		case ERRORS_AND_WARNINGS -> Rule.AlertsThreshold.ERRORS_AND_WARNINGS;
		case ALL -> Rule.AlertsThreshold.ALL;
		};
	}

	private static Rule.SecurityAlertsThreshold mapSecurityAlertsThreshold(
			Drifty.SecurityAlertsThreshold t
	) {
		return switch (t) {
		case NONE -> Rule.SecurityAlertsThreshold.NONE;
		case CRITICAL -> Rule.SecurityAlertsThreshold.CRITICAL;
		case HIGH_OR_HIGHER -> Rule.SecurityAlertsThreshold.HIGH_OR_HIGHER;
		case MEDIUM_OR_HIGHER -> Rule.SecurityAlertsThreshold.MEDIUM_OR_HIGHER;
		case ALL -> Rule.SecurityAlertsThreshold.ALL;
		};
	}

	private static RulePatternArgs mapRulePattern(Drifty.RulePattern rp) {
		return new RulePatternArgs(
				rp.name,
				rp.negate,
				mapPatternOperator(rp.operator),
				rp.pattern
		);
	}

	private static RulePatternArgs.PatternOperator mapPatternOperator(
			Drifty.PatternOperator op
	) {
		return switch (op) {
		case STARTS_WITH -> RulePatternArgs.PatternOperator.STARTS_WITH;
		case ENDS_WITH -> RulePatternArgs.PatternOperator.ENDS_WITH;
		case CONTAINS -> RulePatternArgs.PatternOperator.CONTAINS;
		case REGEX -> RulePatternArgs.PatternOperator.REGEX;
		};
	}

	private static BypassActorArgs mapBypassActor(Drifty.BypassActor ba) {
		return new BypassActorArgs(
				ba.actorId,
				mapActorType(ba.actorType),
				mapBypassMode(ba.bypassMode)
		);
	}

	private static RulesetDetailsResponse.BypassActor.ActorType mapActorType(
			Drifty.ActorType t
	) {
		return switch (t) {
		case INTEGRATION ->
			RulesetDetailsResponse.BypassActor.ActorType.INTEGRATION;
		case ORGANIZATION_ADMIN ->
			RulesetDetailsResponse.BypassActor.ActorType.ORGANIZATION_ADMIN;
		case REPOSITORY_ROLE ->
			RulesetDetailsResponse.BypassActor.ActorType.REPOSITORY_ROLE;
		case TEAM -> RulesetDetailsResponse.BypassActor.ActorType.TEAM;
		};
	}

	private static RulesetDetailsResponse.BypassActor.BypassMode mapBypassMode(
			Drifty.BypassMode m
	) {
		return switch (m) {
		case ALWAYS -> RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS;
		case PULL_REQUEST ->
			RulesetDetailsResponse.BypassActor.BypassMode.PULL_REQUEST;
		};
	}

	private static EnvironmentArgs mapEnvironment(
			String name,
			Drifty.Environment e
	) {
		var builder = EnvironmentArgs.builder(name);
		if (!e.secrets.isEmpty()) {
			builder.secrets(e.secrets.toArray(String[]::new));
		}
		if (e.waitTimer > 0) {
			builder.waitTimer((int) e.waitTimer);
		}
		if (e.protectedBranches || e.customBranchPolicies) {
			builder.deploymentBranchPolicy(
					e.protectedBranches,
					e.customBranchPolicies
			);
		}
		return builder.build();
	}

}
