package io.github.arlol.githubcheck;

import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.RulePatternOperator;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.client.TeamResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Spells a client enum the way {@code config/drifty.pkl} declares it. The
 * inverse of {@link PklTypes}, which reads the same unions the other way.
 * <p>
 * Three vocabularies name the same value and only one of them belongs in an
 * exported file or an {@code actual/*} record: the Java constant name
 * ({@code ORGANIZATION_ADMIN}), GitHub's wire spelling ({@code @JsonProperty}),
 * and the schema's own literal ({@code "OrganizationAdmin"}). The export writes
 * the third, because the adopter loads the file back through Pkl; the
 * comparison uses it too, so both sides of a drift are held to the one
 * vocabulary rather than agreeing by coincidence.
 * <p>
 * Every arm answers a {@code Drifty} constant rather than a string, and
 * {@code Drifty.java} is generated from the schema at build time. A misspelling
 * therefore cannot compile, and renaming a union member breaks the build here
 * instead of producing a file that does not evaluate. Two rules this replaced
 * did neither: lower-casing the constant name, which is wrong for 13 of the 111
 * spellings GitHub uses, and seven hand-written tables of string literals.
 */
public final class ConfigSpelling {

	private ConfigSpelling() {
	}

	public static String of(RulesetTarget value) {
		return value == null ? null : (switch (value) {
		case BRANCH -> Drifty.RulesetTarget.BRANCH;
		case TAG -> Drifty.RulesetTarget.TAG;
		case PUSH -> Drifty.RulesetTarget.PUSH;
		case REPOSITORY -> Drifty.RulesetTarget.REPOSITORY;
		}).toString();
	}

	public static String of(RulesetEnforcement value) {
		return value == null ? null : (switch (value) {
		case ACTIVE -> Drifty.RulesetEnforcement.ACTIVE;
		case EVALUATE -> Drifty.RulesetEnforcement.EVALUATE;
		case DISABLED -> Drifty.RulesetEnforcement.DISABLED;
		}).toString();
	}

	public static String of(RulePatternOperator value) {
		return value == null ? null : (switch (value) {
		case STARTS_WITH -> Drifty.PatternOperator.STARTS_WITH;
		case ENDS_WITH -> Drifty.PatternOperator.ENDS_WITH;
		case CONTAINS -> Drifty.PatternOperator.CONTAINS;
		case REGEX -> Drifty.PatternOperator.REGEX;
		}).toString();
	}

	public static String of(
			RulesetDetailsResponse.BypassActor.ActorType value
	) {
		return value == null ? null : (switch (value) {
		case INTEGRATION -> Drifty.ActorType.INTEGRATION;
		case ORGANIZATION_ADMIN -> Drifty.ActorType.ORGANIZATION_ADMIN;
		case REPOSITORY_ROLE -> Drifty.ActorType.REPOSITORY_ROLE;
		case TEAM -> Drifty.ActorType.TEAM;
		case DEPLOY_KEY -> Drifty.ActorType.DEPLOY_KEY;
		case USER -> Drifty.ActorType.USER;
		}).toString();
	}

	public static String of(
			RulesetDetailsResponse.BypassActor.BypassMode value
	) {
		return value == null ? null : (switch (value) {
		case ALWAYS -> Drifty.BypassMode.ALWAYS;
		case PULL_REQUEST -> Drifty.BypassMode.PULL_REQUEST;
		case EXEMPT -> Drifty.BypassMode.EXEMPT;
		}).toString();
	}

	public static String of(TeamResponse.Privacy value) {
		return value == null ? null : (switch (value) {
		case CLOSED -> Drifty.TeamPrivacy.CLOSED;
		case SECRET -> Drifty.TeamPrivacy.SECRET;
		}).toString();
	}

	public static String of(TeamResponse.NotificationSetting value) {
		return value == null ? null : (switch (value) {
		case NOTIFICATIONS_ENABLED ->
			Drifty.TeamNotificationSetting.NOTIFICATIONS_ENABLED;
		case NOTIFICATIONS_DISABLED ->
			Drifty.TeamNotificationSetting.NOTIFICATIONS_DISABLED;
		}).toString();
	}

	public static String of(ActionsEnabledRepositories value) {
		return value == null ? null : (switch (value) {
		case ALL -> Drifty.ActionsEnabledRepositories.ALL;
		case NONE -> Drifty.ActionsEnabledRepositories.NONE;
		case SELECTED -> Drifty.ActionsEnabledRepositories.SELECTED;
		}).toString();
	}

	public static String of(AllowedActions value) {
		return value == null ? null : (switch (value) {
		case ALL -> Drifty.AllowedActions.ALL;
		case LOCAL_ONLY -> Drifty.AllowedActions.LOCAL_ONLY;
		case SELECTED -> Drifty.AllowedActions.SELECTED;
		}).toString();
	}

	public static String of(SecretVisibility value) {
		return value == null ? null : (switch (value) {
		case ALL -> Drifty.SecretVisibility.ALL;
		case PRIVATE -> Drifty.SecretVisibility.PRIVATE;
		case SELECTED -> Drifty.SecretVisibility.SELECTED;
		}).toString();
	}

	public static String of(
			WorkflowPermissions.DefaultWorkflowPermissions value
	) {
		return value == null ? null : (switch (value) {
		case READ -> Drifty.WorkflowPermissions.READ;
		case WRITE -> Drifty.WorkflowPermissions.WRITE;
		}).toString();
	}

	public static String of(RepositoryVisibility value) {
		return value == null ? null : (switch (value) {
		case PUBLIC -> Drifty.Visibility.PUBLIC;
		case PRIVATE -> Drifty.Visibility.PRIVATE;
		case INTERNAL -> Drifty.Visibility.INTERNAL;
		}).toString();
	}

	/**
	 * The one spelling here with no {@code Drifty} constant to point at:
	 * {@code Pages.buildType} is declared as an inline union in
	 * {@code config/drifty.pkl}, so the code generator writes it as a
	 * {@code String} field and there is nothing for an arm to name. Renaming
	 * either member of that union is the one vocabulary change this class does
	 * not catch at compile time.
	 */
	public static String of(PagesBuildType value) {
		return value == null ? null : switch (value) {
		case WORKFLOW -> "workflow";
		case LEGACY -> "legacy";
		};
	}

}
