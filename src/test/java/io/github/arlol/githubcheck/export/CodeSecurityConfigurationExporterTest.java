package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration.BypassReviewer;

class CodeSecurityConfigurationExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	/**
	 * Every wire-named setting at the schema's own default, so a case only
	 * needs to override the one field it is testing.
	 */
	private static Map<String, String> defaultSettings() {
		var settings = new LinkedHashMap<String, String>();
		settings.put("advanced_security", "disabled");
		settings.put("dependency_graph", "enabled");
		settings.put("dependency_graph_autosubmit_action", "disabled");
		settings.put("dependabot_alerts", "disabled");
		settings.put("dependabot_security_updates", "disabled");
		settings.put("dependabot_delegated_alert_dismissal", "disabled");
		settings.put("code_scanning_default_setup", "disabled");
		settings.put("code_scanning_delegated_alert_dismissal", "not_set");
		settings.put("secret_scanning", "disabled");
		settings.put("secret_scanning_push_protection", "disabled");
		settings.put("secret_scanning_delegated_bypass", "disabled");
		settings.put("secret_scanning_validity_checks", "disabled");
		settings.put("secret_scanning_non_provider_patterns", "disabled");
		settings.put("secret_scanning_generic_secrets", "disabled");
		settings.put("secret_scanning_delegated_alert_dismissal", "not_set");
		settings.put("private_vulnerability_reporting", "disabled");
		return settings;
	}

	private static ActualCodeSecurityConfiguration configuration(String name) {
		return new ActualCodeSecurityConfiguration(
				1L,
				name,
				"",
				defaultSettings(),
				"enforced",
				false,
				"not_set",
				null,
				null,
				Set.of(),
				"none",
				Set.of()
		);
	}

	@Test
	void aConfigurationAtGitHubsDefaultsExportsNoMembers() {
		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(configuration("baseline"), DEFAULTS);

		assertThat(field.name()).isEqualTo("baseline");
		assertThat(PklWriter.write(field.value())).isEqualTo("");
	}

	@Test
	void aSettingDifferingFromTheDefaultIsEmittedUnderItsSchemaName() {
		var base = configuration("baseline");
		var settings = new LinkedHashMap<>(base.settings());
		// secret_scanning is the wire name
		// OrgCodeSecurityConfigurationsDriftGroup
		// pairs with secretScanning; a wrong pairing in the exporter would
		// either miss this or emit a different field.
		settings.put("secret_scanning", "enabled");
		var actual = new ActualCodeSecurityConfiguration(
				base.id(),
				base.name(),
				base.description(),
				settings,
				base.enforcement(),
				base.dependencyGraphAutosubmitLabeledRunners(),
				base.codeScanningRunnerType(),
				base.codeScanningRunnerLabel(),
				base.codeScanningAllowAdvanced(),
				base.secretScanningDelegatedBypassReviewers(),
				base.defaultForNewRepos(),
				base.repositories()
		);

		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(actual, DEFAULTS);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				secretScanning = "enabled"
				""");
	}

	/**
	 * GitHub returns {@code dependency_graph_autosubmit_action_options} on
	 * every configuration, its own included, so this toggle is compared
	 * unconditionally like the sixteen wire settings — no presence guard to
	 * exercise, unlike the three option sub-objects below.
	 */
	@Test
	void dependencyGraphAutosubmitLabeledRunnersDifferingFromTheDefaultIsEmitted() {
		var base = configuration("baseline");
		var actual = new ActualCodeSecurityConfiguration(
				base.id(),
				base.name(),
				base.description(),
				base.settings(),
				base.enforcement(),
				true,
				base.codeScanningRunnerType(),
				base.codeScanningRunnerLabel(),
				base.codeScanningAllowAdvanced(),
				base.secretScanningDelegatedBypassReviewers(),
				base.defaultForNewRepos(),
				base.repositories()
		);

		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(actual, DEFAULTS);

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				dependencyGraphAutosubmitLabeledRunners = true
				""");
	}

	@Test
	void codeScanningOptionsAreOmittedWhenGitHubReportsNotSet() {
		// Non-default on purpose: runnerType is "not_set" (the sentinel
		// ActualTypes uses for "no options object"), never null, so a guard
		// checking nullness rather than the sentinel would wrongly emit this
		// for every configuration.
		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(configuration("baseline"), DEFAULTS);

		assertThat(PklWriter.write(field.value()))
				.doesNotContain("codeScanningDefaultSetupOptions");
	}

	@Test
	void codeScanningOptionsAreEmittedWithANoteWhenGitHubHasSetOne() {
		var base = configuration("baseline");
		var actual = new ActualCodeSecurityConfiguration(
				base.id(),
				base.name(),
				base.description(),
				base.settings(),
				base.enforcement(),
				base.dependencyGraphAutosubmitLabeledRunners(),
				"labeled",
				"gpu",
				base.codeScanningAllowAdvanced(),
				base.secretScanningDelegatedBypassReviewers(),
				base.defaultForNewRepos(),
				base.repositories()
		);

		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(actual, DEFAULTS);

		assertThat(PklWriter.write(field.value())).isEqualTo(
				"""
						codeScanningDefaultSetupOptions {
						  runnerType = "labeled"
						  runnerLabel = "gpu"
						}
						// setting these options means drifty compares them from now on; GitHub picks a
						// runner type itself when default setup is enabled
						"""
		);
	}

	/**
	 * {@code allowAdvanced} is a plain nullable field rather than a sentinel,
	 * unlike {@code codeScanningRunnerType}: GitHub omits
	 * {@code code_scanning_options} entirely, or answers the field within it as
	 * null, on a configuration that never set it, so
	 * {@code ActualTypes.codeSecurityConfiguration} already collapses both of
	 * those cases to {@code null} and the guard here only needs nullness.
	 */
	@Test
	void codeScanningOptionsIsOmittedWhenGitHubReportsItAsUnset() {
		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(configuration("baseline"), DEFAULTS);

		assertThat(PklWriter.write(field.value()))
				.doesNotContain("codeScanningOptions");
	}

	@Test
	void codeScanningOptionsIsEmittedWithANoteWhenGitHubHasSetIt() {
		var base = configuration("baseline");
		var actual = new ActualCodeSecurityConfiguration(
				base.id(),
				base.name(),
				base.description(),
				base.settings(),
				base.enforcement(),
				base.dependencyGraphAutosubmitLabeledRunners(),
				base.codeScanningRunnerType(),
				base.codeScanningRunnerLabel(),
				true,
				base.secretScanningDelegatedBypassReviewers(),
				base.defaultForNewRepos(),
				base.repositories()
		);

		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(actual, DEFAULTS);

		assertThat(PklWriter.write(field.value())).isEqualTo(
				"""
						codeScanningOptions {
						  allowAdvanced = true
						}
						// setting these options means drifty compares them from now on; GitHub picks a
						// runner type itself when default setup is enabled
						"""
		);
	}

	@Test
	void secretScanningBypassOptionsAreEmittedWithANoteWhenGitHubHasReviewers() {
		var base = configuration("baseline");
		var actual = new ActualCodeSecurityConfiguration(
				base.id(),
				base.name(),
				base.description(),
				base.settings(),
				base.enforcement(),
				base.dependencyGraphAutosubmitLabeledRunners(),
				base.codeScanningRunnerType(),
				base.codeScanningRunnerLabel(),
				base.codeScanningAllowAdvanced(),
				Set.of(new BypassReviewer("Team", 7L, "ALWAYS")),
				base.defaultForNewRepos(),
				base.repositories()
		);

		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(actual, DEFAULTS);

		assertThat(PklWriter.write(field.value())).isEqualTo(
				"""
						secretScanningDelegatedBypassOptions {
						  reviewers {
						    new {
						      reviewerId = 7
						      reviewerType = "Team"
						      mode = "ALWAYS"
						    }
						  }
						}
						// setting these options means drifty compares them from now on; GitHub picks a
						// runner type itself when default setup is enabled
						"""
		);
	}

	/**
	 * The note explains all three option sub-objects at once; emitting it once
	 * per sub-object present would repeat the same line when more than one is
	 * set on the same configuration.
	 */
	@Test
	void theOptionsNoteIsEmittedOnceWhenTwoOptionSubObjectsAreSet() {
		var base = configuration("baseline");
		var actual = new ActualCodeSecurityConfiguration(
				base.id(),
				base.name(),
				base.description(),
				base.settings(),
				base.enforcement(),
				base.dependencyGraphAutosubmitLabeledRunners(),
				"labeled",
				"gpu",
				base.codeScanningAllowAdvanced(),
				Set.of(new BypassReviewer("Team", 7L, "ALWAYS")),
				base.defaultForNewRepos(),
				base.repositories()
		);

		var field = (PklNode.Field) CodeSecurityConfigurationExporter
				.entry(actual, DEFAULTS);

		String written = PklWriter.write(field.value());
		int noteCount = written.split(
				"setting these options means drifty compares them",
				-1
		).length - 1;
		assertThat(noteCount).isEqualTo(1);
	}

}
