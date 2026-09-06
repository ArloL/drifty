package io.github.arlol.githubcheck.testsupport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Desired-state fixtures for tests: the Pkl-generated {@code Drifty.*} types
 * with the defaults {@code config/drifty.pkl} gives them.
 * <p>
 * The defaults are evaluated from the schema itself, once per JVM, rather than
 * restated here. Tests take one of these and change what they care about with
 * the generated {@code withX} methods — {@code Desired.repository("r")
 * .withArchived(true)} — so adding a field to the schema adds nothing here. The
 * previous hand-written builders were a 1,900-line second copy of the schema
 * that had to be extended field by field alongside it.
 * <p>
 * Types with no defaulted properties ({@link Drifty.BypassActor},
 * {@link Drifty.SecretScanningBypassReviewer}) are built directly.
 */
public final class Desired {

	private static final Path DEFAULTS = Path
			.of("src/test/resources/desired-defaults.pkl")
			.toAbsolutePath();

	private static final Drifty.Organization ORGANIZATION;
	private static final Drifty.Repository REPOSITORY;
	private static final Drifty.Ruleset RULESET;
	private static final Drifty.PullRequestRule PULL_REQUEST_RULE;
	private static final Drifty.MergeQueueRule MERGE_QUEUE_RULE;
	private static final Drifty.WorkflowRule WORKFLOW_RULE;
	private static final Drifty.BranchProtection BRANCH_PROTECTION;
	private static final Drifty.Environment ENVIRONMENT;
	private static final Drifty.Pages PAGES;
	private static final Drifty.StatusCheck STATUS_CHECK;
	private static final Drifty.CodeScanningTool CODE_SCANNING_TOOL;
	private static final Drifty.RulePattern RULE_PATTERN;
	private static final Drifty.ActionsPermissions ACTIONS_PERMISSIONS;
	private static final Drifty.SelectedActions SELECTED_ACTIONS;
	private static final Drifty.OrgSecret ORG_SECRET;
	private static final Drifty.OrgVariable ORG_VARIABLE;
	private static final Drifty.Webhook WEBHOOK;
	private static final Drifty.CustomProperty CUSTOM_PROPERTY;
	private static final Drifty.OrgRuleset ORG_RULESET;
	private static final Drifty.PropertyCondition PROPERTY_CONDITION;
	private static final Drifty.CodeSecurityConfiguration CODE_SECURITY_CONFIGURATION;
	private static final Drifty.Team TEAM;
	private static final Drifty.RunnerGroup RUNNER_GROUP;

	static {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			Config root = evaluator.evaluate(ModuleSource.path(DEFAULTS));
			ORGANIZATION = root.get("organization")
					.as(Drifty.Organization.class);
			REPOSITORY = root.get("repository").as(Drifty.Repository.class);
			RULESET = root.get("ruleset").as(Drifty.Ruleset.class);
			PULL_REQUEST_RULE = root.get("pullRequestRule")
					.as(Drifty.PullRequestRule.class);
			MERGE_QUEUE_RULE = root.get("mergeQueueRule")
					.as(Drifty.MergeQueueRule.class);
			WORKFLOW_RULE = root.get("workflowRule")
					.as(Drifty.WorkflowRule.class);
			BRANCH_PROTECTION = root.get("branchProtection")
					.as(Drifty.BranchProtection.class);
			ENVIRONMENT = root.get("environment").as(Drifty.Environment.class);
			PAGES = root.get("pages").as(Drifty.Pages.class);
			STATUS_CHECK = root.get("statusCheck").as(Drifty.StatusCheck.class);
			CODE_SCANNING_TOOL = root.get("codeScanningTool")
					.as(Drifty.CodeScanningTool.class);
			RULE_PATTERN = root.get("rulePattern").as(Drifty.RulePattern.class);
			ACTIONS_PERMISSIONS = root.get("actionsPermissions")
					.as(Drifty.ActionsPermissions.class);
			SELECTED_ACTIONS = root.get("selectedActions")
					.as(Drifty.SelectedActions.class);
			ORG_SECRET = root.get("orgSecret").as(Drifty.OrgSecret.class);
			ORG_VARIABLE = root.get("orgVariable").as(Drifty.OrgVariable.class);
			WEBHOOK = root.get("webhook").as(Drifty.Webhook.class);
			CUSTOM_PROPERTY = root.get("customProperty")
					.as(Drifty.CustomProperty.class);
			ORG_RULESET = root.get("orgRuleset").as(Drifty.OrgRuleset.class);
			PROPERTY_CONDITION = root.get("propertyCondition")
					.as(Drifty.PropertyCondition.class);
			CODE_SECURITY_CONFIGURATION = root.get("codeSecurityConfiguration")
					.as(Drifty.CodeSecurityConfiguration.class);
			TEAM = root.get("team").as(Drifty.Team.class);
			RUNNER_GROUP = root.get("runnerGroup").as(Drifty.RunnerGroup.class);
		}
	}

	private Desired() {
	}

	/**
	 * An organization with GitHub's defaults, which is what the schema
	 * declares.
	 */
	public static Drifty.Organization organization() {
		return ORGANIZATION;
	}

	/**
	 * A repository with GitHub's defaults, which is what the schema declares.
	 */
	public static Drifty.Repository repository(String name) {
		return REPOSITORY.withName(name);
	}

	public static Drifty.Ruleset ruleset() {
		return RULESET;
	}

	/** A pull_request rule with GitHub's defaults for a new one. */
	public static Drifty.PullRequestRule pullRequestRule() {
		return PULL_REQUEST_RULE;
	}

	/** A merge_queue rule with GitHub's defaults for a new one. */
	public static Drifty.MergeQueueRule mergeQueueRule() {
		return MERGE_QUEUE_RULE;
	}

	public static Drifty.WorkflowRule workflowRule(
			String path,
			long repositoryId
	) {
		return WORKFLOW_RULE.withPath(path).withRepositoryId(repositoryId);
	}

	public static Drifty.BranchProtection branchProtection() {
		return BRANCH_PROTECTION;
	}

	public static Drifty.Environment environment() {
		return ENVIRONMENT;
	}

	/** A workflow-built Pages site, the schema's default build type. */
	public static Drifty.Pages pages() {
		return PAGES;
	}

	public static Drifty.Pages legacyPages(
			String sourceBranch,
			String sourcePath
	) {
		return PAGES.withBuildType("legacy")
				.withSourceBranch(sourceBranch)
				.withSourcePath(sourcePath);
	}

	/** A status check any app may report. */
	public static Drifty.StatusCheck statusCheck(String context) {
		return STATUS_CHECK.withContext(context);
	}

	public static Drifty.StatusCheck statusCheck(String context, long appId) {
		return STATUS_CHECK.withContext(context).withAppId(appId);
	}

	public static Drifty.CodeScanningTool codeScanningTool(String tool) {
		return CODE_SCANNING_TOOL.withTool(tool);
	}

	public static Drifty.RulePattern rulePattern(
			Drifty.PatternOperator operator,
			String pattern
	) {
		return RULE_PATTERN.withOperator(operator).withPattern(pattern);
	}

	/** An organization's Actions policy with GitHub's defaults. */
	public static Drifty.ActionsPermissions actionsPermissions() {
		return ACTIONS_PERMISSIONS;
	}

	/** An Actions allow-list with GitHub's defaults. */
	public static Drifty.SelectedActions selectedActions() {
		return SELECTED_ACTIONS;
	}

	/** An organization secret with the default {@code private} visibility. */
	public static Drifty.OrgSecret orgSecret() {
		return ORG_SECRET;
	}

	/** An organization variable with the default {@code private} visibility. */
	public static Drifty.OrgVariable orgVariable(String value) {
		return ORG_VARIABLE.withValue(value);
	}

	/** A webhook with GitHub's defaults: form content, push events, active. */
	public static Drifty.Webhook webhook(String url) {
		return WEBHOOK.withUrl(url);
	}

	/** A custom property definition of the given type with the defaults. */
	public static Drifty.CustomProperty customProperty(
			Drifty.CustomPropertyValueType valueType
	) {
		return CUSTOM_PROPERTY.withValueType(valueType);
	}

	/**
	 * An organization ruleset with the defaults: every repository, no rules.
	 */
	public static Drifty.OrgRuleset orgRuleset() {
		return ORG_RULESET;
	}

	/** A custom-source property condition on {@code name}. */
	public static Drifty.PropertyCondition propertyCondition(
			String name,
			List<String> values
	) {
		return PROPERTY_CONDITION.withName(name).withPropertyValues(values);
	}

	/** A code security configuration with GitHub's POST defaults. */
	public static Drifty.CodeSecurityConfiguration codeSecurityConfiguration() {
		return CODE_SECURITY_CONFIGURATION;
	}

	/** A team with the defaults: closed, notifications on, no members. */
	public static Drifty.Team team() {
		return TEAM;
	}

	/** A runner group with the defaults: visible to all, no restrictions. */
	public static Drifty.RunnerGroup runnerGroup() {
		return RUNNER_GROUP;
	}

	public static Drifty.BypassActor bypassActor(
			long actorId,
			Drifty.ActorType actorType,
			Drifty.BypassMode bypassMode
	) {
		return new Drifty.BypassActor(actorId, actorType, bypassMode);
	}

	public static Drifty.SecretScanningBypassReviewer bypassReviewer(
			long reviewerId,
			Drifty.SecretScanningBypassReviewerType reviewerType
	) {
		return new Drifty.SecretScanningBypassReviewer(
				reviewerId,
				reviewerType
		);
	}

	// ─── Keyed sections
	// ──────────────────────────────────────────────────────
	//
	// Branch protections, rulesets and environments are maps keyed by pattern
	// or name. These add one entry, keeping whatever the repository already
	// declares, which is what most tests want when they build on a base
	// repository.

	public static Drifty.Repository withBranchProtection(
			Drifty.Repository repository,
			String pattern,
			Drifty.BranchProtection branchProtection
	) {
		return repository.withBranchProtections(
				plus(repository.branchProtections, pattern, branchProtection)
		);
	}

	public static Drifty.Repository withRuleset(
			Drifty.Repository repository,
			String name,
			Drifty.Ruleset ruleset
	) {
		var copy = new LinkedHashMap<String, Drifty.Ruleset>(
				repository.rulesets
		);
		copy.put(name, ruleset);
		return repository.withRulesets(Map.copyOf(copy));
	}

	public static Drifty.Repository withEnvironment(
			Drifty.Repository repository,
			String name,
			Drifty.Environment environment
	) {
		return repository.withEnvironments(
				plus(repository.environments, name, environment)
		);
	}

	private static <V> Map<String, V> plus(
			Map<String, V> map,
			String key,
			V value
	) {
		var copy = new LinkedHashMap<>(map);
		copy.put(key, value);
		return Map.copyOf(copy);
	}

}
