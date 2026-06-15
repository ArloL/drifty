package io.github.arlol.githubcheck;

import java.util.Locale;

import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulePatternOperator;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Converts the Pkl-generated {@code Drifty.*} string-enums into the GitHub
 * client enums the drift comparison and request paths use. The Pkl-generated
 * configuration types are the single source of desired state; these are the
 * only translations the consuming code needs.
 */
public final class PklTypes {

	private PklTypes() {
	}

	public static PagesBuildType pagesBuildType(String buildType) {
		return PagesBuildType.valueOf(buildType.toUpperCase(Locale.ROOT));
	}

	public static RepositoryVisibility visibility(Drifty.Visibility v) {
		return switch (v) {
		case PUBLIC -> RepositoryVisibility.PUBLIC;
		case PRIVATE -> RepositoryVisibility.PRIVATE;
		case INTERNAL -> RepositoryVisibility.INTERNAL;
		};
	}

	public static WorkflowPermissions.DefaultWorkflowPermissions workflowPermissions(
			Drifty.WorkflowPermissions v
	) {
		return switch (v) {
		case READ -> WorkflowPermissions.DefaultWorkflowPermissions.READ;
		case WRITE -> WorkflowPermissions.DefaultWorkflowPermissions.WRITE;
		};
	}

	public static Rule.AlertsThreshold alertsThreshold(
			Drifty.AlertsThreshold t
	) {
		return switch (t) {
		case NONE -> Rule.AlertsThreshold.NONE;
		case ERRORS -> Rule.AlertsThreshold.ERRORS;
		case ERRORS_AND_WARNINGS -> Rule.AlertsThreshold.ERRORS_AND_WARNINGS;
		case ALL -> Rule.AlertsThreshold.ALL;
		};
	}

	public static Rule.SecurityAlertsThreshold securityAlertsThreshold(
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

	public static RulePatternOperator patternOperator(
			Drifty.PatternOperator op
	) {
		return switch (op) {
		case STARTS_WITH -> RulePatternOperator.STARTS_WITH;
		case ENDS_WITH -> RulePatternOperator.ENDS_WITH;
		case CONTAINS -> RulePatternOperator.CONTAINS;
		case REGEX -> RulePatternOperator.REGEX;
		};
	}

	public static SecurityAndAnalysis.BypassReviewer.ReviewerType reviewerType(
			Drifty.SecretScanningBypassReviewerType t
	) {
		return switch (t) {
		case TEAM -> SecurityAndAnalysis.BypassReviewer.ReviewerType.TEAM;
		case ROLE -> SecurityAndAnalysis.BypassReviewer.ReviewerType.ROLE;
		};
	}

	public static RulesetDetailsResponse.BypassActor.ActorType actorType(
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

	public static RulesetDetailsResponse.BypassActor.BypassMode bypassMode(
			Drifty.BypassMode m
	) {
		return switch (m) {
		case ALWAYS -> RulesetDetailsResponse.BypassActor.BypassMode.ALWAYS;
		case PULL_REQUEST ->
			RulesetDetailsResponse.BypassActor.BypassMode.PULL_REQUEST;
		};
	}

	public static SquashMergeCommitTitle squashMergeCommitTitle(
			Drifty.SquashMergeCommitTitle v
	) {
		return SquashMergeCommitTitle.valueOf(v.name());
	}

	public static SquashMergeCommitMessage squashMergeCommitMessage(
			Drifty.SquashMergeCommitMessage v
	) {
		return SquashMergeCommitMessage.valueOf(v.name());
	}

	public static MergeCommitTitle mergeCommitTitle(Drifty.MergeCommitTitle v) {
		return MergeCommitTitle.valueOf(v.name());
	}

	public static MergeCommitMessage mergeCommitMessage(
			Drifty.MergeCommitMessage v
	) {
		return MergeCommitMessage.valueOf(v.name());
	}

}
