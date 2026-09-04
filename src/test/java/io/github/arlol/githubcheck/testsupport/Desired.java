package io.github.arlol.githubcheck.testsupport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
	private static final Drifty.BranchProtection BRANCH_PROTECTION;
	private static final Drifty.Environment ENVIRONMENT;
	private static final Drifty.Pages PAGES;
	private static final Drifty.StatusCheck STATUS_CHECK;
	private static final Drifty.CodeScanningTool CODE_SCANNING_TOOL;
	private static final Drifty.RulePattern RULE_PATTERN;
	private static final Drifty.ActionsPermissions ACTIONS_PERMISSIONS;
	private static final Drifty.SelectedActions SELECTED_ACTIONS;

	static {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			Config root = evaluator.evaluate(ModuleSource.path(DEFAULTS));
			ORGANIZATION = root.get("organization")
					.as(Drifty.Organization.class);
			REPOSITORY = root.get("repository").as(Drifty.Repository.class);
			RULESET = root.get("ruleset").as(Drifty.Ruleset.class);
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
		return repository
				.withRulesets(plus(repository.rulesets, name, ruleset));
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
