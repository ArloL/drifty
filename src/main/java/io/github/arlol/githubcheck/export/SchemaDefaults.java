package io.github.arlol.githubcheck.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The schema's defaults, as {@code Drifty.*} instances.
 * <p>
 * Evaluated from the schema rather than restated in Java, so a field added to
 * {@code config/drifty.pkl} needs no change here — the same reason
 * {@code testsupport.Desired} builds its fixtures this way.
 * <p>
 * The URI is the one the exported file amends. Diffing against a different copy
 * of the schema than the file resolves to would put values in the file that its
 * own defaults already imply, or leave out ones they do not.
 */
public final class SchemaDefaults {

	public static final String MAIN_SCHEMA_URI = "https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl";

	private static final String TEMPLATE = "/export-defaults.pkl";

	private final String uri;
	private final Drifty.Organization organization;
	private final Drifty.Repository repository;
	private final Drifty.Ruleset ruleset;
	private final Drifty.OrgRuleset orgRuleset;
	private final Drifty.PropertyCondition propertyCondition;
	private final Drifty.PullRequestRule pullRequestRule;
	private final Drifty.RulePattern rulePattern;
	private final Drifty.MergeQueueRule mergeQueueRule;
	private final Drifty.BranchProtection branchProtection;
	private final Drifty.Environment environment;
	private final Drifty.Pages pages;
	private final Drifty.Webhook webhook;
	private final Drifty.CustomProperty customProperty;
	private final Drifty.CodeSecurityConfiguration codeSecurityConfiguration;
	private final Drifty.CodeScanningDefaultSetupOptions codeScanningDefaultSetupOptions;
	private final Drifty.CodeScanningOptions codeScanningOptions;
	private final Drifty.Team team;
	private final Drifty.RunnerGroup runnerGroup;
	private final Drifty.OrgSecret orgSecret;
	private final Drifty.OrgVariable orgVariable;
	private final Drifty.ActionsPermissions actionsPermissions;
	private final Drifty.SelectedActions selectedActions;

	private SchemaDefaults(String uri, Config root) {
		this.uri = uri;
		organization = root.get("organization").as(Drifty.Organization.class);
		repository = root.get("repository").as(Drifty.Repository.class);
		ruleset = root.get("ruleset").as(Drifty.Ruleset.class);
		orgRuleset = root.get("orgRuleset").as(Drifty.OrgRuleset.class);
		propertyCondition = root.get("propertyCondition")
				.as(Drifty.PropertyCondition.class);
		pullRequestRule = root.get("pullRequestRule")
				.as(Drifty.PullRequestRule.class);
		rulePattern = root.get("rulePattern").as(Drifty.RulePattern.class);
		mergeQueueRule = root.get("mergeQueueRule")
				.as(Drifty.MergeQueueRule.class);
		branchProtection = root.get("branchProtection")
				.as(Drifty.BranchProtection.class);
		environment = root.get("environment").as(Drifty.Environment.class);
		pages = root.get("pages").as(Drifty.Pages.class);
		webhook = root.get("webhook").as(Drifty.Webhook.class);
		customProperty = root.get("customProperty")
				.as(Drifty.CustomProperty.class);
		codeSecurityConfiguration = root.get("codeSecurityConfiguration")
				.as(Drifty.CodeSecurityConfiguration.class);
		codeScanningDefaultSetupOptions = root
				.get("codeScanningDefaultSetupOptions")
				.as(Drifty.CodeScanningDefaultSetupOptions.class);
		codeScanningOptions = root.get("codeScanningOptions")
				.as(Drifty.CodeScanningOptions.class);
		team = root.get("team").as(Drifty.Team.class);
		runnerGroup = root.get("runnerGroup").as(Drifty.RunnerGroup.class);
		orgSecret = root.get("orgSecret").as(Drifty.OrgSecret.class);
		orgVariable = root.get("orgVariable").as(Drifty.OrgVariable.class);
		actionsPermissions = root.get("actionsPermissions")
				.as(Drifty.ActionsPermissions.class);
		selectedActions = root.get("selectedActions")
				.as(Drifty.SelectedActions.class);
	}

	/**
	 * @param schemaUri where the schema lives — the main-branch URL by default,
	 *                  a local path in tests. Normalized once, here, into the
	 *                  URI both the evaluation below and {@link #uri()} use:
	 *                  the file's {@code amends} line is built from the latter,
	 *                  so the two can never name the schema differently the way
	 *                  a raw Windows path interpolated separately into each
	 *                  did.
	 */
	public static SchemaDefaults of(String schemaUri) {
		String uri = importUri(schemaUri);
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			return new SchemaDefaults(
					uri,
					evaluator.evaluate(
							ModuleSource
									.text(template().replace("@schema@", uri))
					)
			);
		}
	}

	/**
	 * A module evaluated from text has no base URI, so Pkl refuses a relative
	 * import outright rather than guessing what it is relative to. The two
	 * shapes this ever receives are an {@code http(s)} URL (the production
	 * default, and whatever a user's {@code --schema} names) and a bare
	 * filesystem path (what a local {@code --schema} or the tests give it) — so
	 * those are the two cases handled, rather than parsed as a generic URI:
	 * {@link URI#create(String)} throws on a space, which an ordinary home
	 * directory can contain, and silently misreads a Windows {@code C:/...}
	 * path as an already-schemed URI with scheme {@code C}.
	 * {@link Path#toUri()} both escapes correctly and resolves the path against
	 * the working directory, giving an absolute {@code file:} URI in every
	 * case.
	 */
	private static String importUri(String schemaUri) {
		if (schemaUri.startsWith("http://")
				|| schemaUri.startsWith("https://")) {
			return schemaUri;
		}
		return Path.of(schemaUri).toUri().toString();
	}

	private static String template() {
		try (var in = SchemaDefaults.class.getResourceAsStream(TEMPLATE)) {
			return new String(
					Objects.requireNonNull(in, TEMPLATE).readAllBytes(),
					StandardCharsets.UTF_8
			);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The normalized URI the schema was actually evaluated from — a
	 * {@code file:} URI with forward slashes when {@code schemaUri} named a
	 * filesystem path, unchanged when it was already an {@code http(s)} URL.
	 * This, not the raw {@code schemaUri} passed to {@link #of}, is what
	 * {@code DriftyFileExporter} writes into the exported file's {@code amends}
	 * line.
	 */
	public String uri() {
		return uri;
	}

	public Drifty.Organization organization() {
		return organization;
	}

	public Drifty.Repository repository() {
		return repository;
	}

	public Drifty.Ruleset ruleset() {
		return ruleset;
	}

	public Drifty.OrgRuleset orgRuleset() {
		return orgRuleset;
	}

	public Drifty.PropertyCondition propertyCondition() {
		return propertyCondition;
	}

	public Drifty.PullRequestRule pullRequestRule() {
		return pullRequestRule;
	}

	public Drifty.RulePattern rulePattern() {
		return rulePattern;
	}

	public Drifty.MergeQueueRule mergeQueueRule() {
		return mergeQueueRule;
	}

	public Drifty.BranchProtection branchProtection() {
		return branchProtection;
	}

	public Drifty.Environment environment() {
		return environment;
	}

	public Drifty.Pages pages() {
		return pages;
	}

	public Drifty.Webhook webhook() {
		return webhook;
	}

	public Drifty.CustomProperty customProperty() {
		return customProperty;
	}

	public Drifty.CodeSecurityConfiguration codeSecurityConfiguration() {
		return codeSecurityConfiguration;
	}

	public Drifty.CodeScanningDefaultSetupOptions codeScanningDefaultSetupOptions() {
		return codeScanningDefaultSetupOptions;
	}

	public Drifty.CodeScanningOptions codeScanningOptions() {
		return codeScanningOptions;
	}

	public Drifty.Team team() {
		return team;
	}

	public Drifty.RunnerGroup runnerGroup() {
		return runnerGroup;
	}

	public Drifty.OrgSecret orgSecret() {
		return orgSecret;
	}

	public Drifty.OrgVariable orgVariable() {
		return orgVariable;
	}

	public Drifty.ActionsPermissions actionsPermissions() {
		return actionsPermissions;
	}

	public Drifty.SelectedActions selectedActions() {
		return selectedActions;
	}

}
