package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration.BypassReviewer;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A code security configuration, as the config lines that differ from the
 * schema's defaults.
 * <p>
 * The sixteen {@code SecuritySetting}/{@code AdvancedSecuritySetting} toggles
 * live in {@code ActualCodeSecurityConfiguration.settings()}, a map keyed by
 * GitHub's wire name; each line below pairs one against the schema field
 * {@code OrgCodeSecurityConfigurationsDriftGroup}'s own {@code Setting} table
 * compares it with, so a wrong pairing here fails the same way a wrong one
 * would there.
 * <p>
 * The three option sub-objects — the default setup runner,
 * {@code codeScanningOptions} and the secret scanning bypass reviewers — are
 * written only when GitHub reports them as set: {@code codeScanningRunnerType}
 * comes back {@code "not_set"} rather than {@code null} when the configuration
 * has no runner options — see {@code ActualTypes.codeSecurityConfiguration} —
 * so that guard compares against the sentinel string, not nullness, while
 * {@code codeScanningAllowAdvanced} is a plain nullable field and is guarded on
 * nullness directly. Setting any one of them is what turns it from "GitHub's
 * own choice" into a value drifty compares and fixes from then on, so a single
 * note explains that once, beside whichever of the three appear.
 * {@code dependencyGraphAutosubmitLabeledRunners} is not among them: GitHub
 * returns its options object on every configuration, so it is an ordinary flat
 * toggle compared unconditionally, like the sixteen wire settings.
 */
public final class CodeSecurityConfigurationExporter {

	private static final String OPTIONS_NOTE = "setting these options means drifty compares them from now on; GitHub picks a runner type itself when default setup is enabled";

	private CodeSecurityConfigurationExporter() {
	}

	public static PklNode.Member entry(
			ActualCodeSecurityConfiguration actual,
			SchemaDefaults defaults
	) {
		Drifty.CodeSecurityConfiguration base = defaults
				.codeSecurityConfiguration();

		List<PklNode.Member> members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"description",
								actual.description(),
								base.description
						),
						Fields.field(
								"advancedSecurity",
								actual.settings().get("advanced_security"),
								base.advancedSecurity.toString()
						),
						Fields.field(
								"dependencyGraph",
								actual.settings().get("dependency_graph"),
								base.dependencyGraph.toString()
						),
						Fields.field(
								"dependencyGraphAutosubmitAction",
								actual.settings()
										.get(
												"dependency_graph_autosubmit_action"
										),
								base.dependencyGraphAutosubmitAction.toString()
						),
						Fields.field(
								"dependencyGraphAutosubmitLabeledRunners",
								actual.dependencyGraphAutosubmitLabeledRunners(),
								base.dependencyGraphAutosubmitLabeledRunners
						),
						Fields.field(
								"dependabotAlerts",
								actual.settings().get("dependabot_alerts"),
								base.dependabotAlerts.toString()
						),
						Fields.field(
								"dependabotSecurityUpdates",
								actual.settings()
										.get("dependabot_security_updates"),
								base.dependabotSecurityUpdates.toString()
						),
						Fields.field(
								"dependabotDelegatedAlertDismissal",
								actual.settings()
										.get(
												"dependabot_delegated_alert_dismissal"
										),
								base.dependabotDelegatedAlertDismissal
										.toString()
						),
						Fields.field(
								"codeScanningDefaultSetup",
								actual.settings()
										.get("code_scanning_default_setup"),
								base.codeScanningDefaultSetup.toString()
						),
						Fields.field(
								"codeScanningDelegatedAlertDismissal",
								actual.settings()
										.get(
												"code_scanning_delegated_alert_dismissal"
										),
								base.codeScanningDelegatedAlertDismissal
										.toString()
						),
						Fields.field(
								"secretScanning",
								actual.settings().get("secret_scanning"),
								base.secretScanning.toString()
						),
						Fields.field(
								"secretScanningPushProtection",
								actual.settings()
										.get("secret_scanning_push_protection"),
								base.secretScanningPushProtection.toString()
						),
						Fields.field(
								"secretScanningDelegatedBypass",
								actual.settings()
										.get(
												"secret_scanning_delegated_bypass"
										),
								base.secretScanningDelegatedBypass.toString()
						),
						Fields.field(
								"secretScanningValidityChecks",
								actual.settings()
										.get("secret_scanning_validity_checks"),
								base.secretScanningValidityChecks.toString()
						),
						Fields.field(
								"secretScanningNonProviderPatterns",
								actual.settings()
										.get(
												"secret_scanning_non_provider_patterns"
										),
								base.secretScanningNonProviderPatterns
										.toString()
						),
						Fields.field(
								"secretScanningGenericSecrets",
								actual.settings()
										.get("secret_scanning_generic_secrets"),
								base.secretScanningGenericSecrets.toString()
						),
						Fields.field(
								"secretScanningDelegatedAlertDismissal",
								actual.settings()
										.get(
												"secret_scanning_delegated_alert_dismissal"
										),
								base.secretScanningDelegatedAlertDismissal
										.toString()
						),
						Fields.field(
								"privateVulnerabilityReporting",
								actual.settings()
										.get("private_vulnerability_reporting"),
								base.privateVulnerabilityReporting.toString()
						),
						Fields.field(
								"enforcement",
								actual.enforcement(),
								base.enforcement.toString()
						),
						Fields.field(
								"defaultForNewRepos",
								actual.defaultForNewRepos(),
								base.defaultForNewRepos.toString()
						),
						Fields.strings(
								"repositories",
								actual.repositories(),
								base.repositories
						)
				)
		);
		boolean anyOptions = false;
		if (!"not_set".equals(actual.codeScanningRunnerType())) {
			members.add(
					codeScanningDefaultSetupOptions(
							actual,
							defaults.codeScanningDefaultSetupOptions()
					)
			);
			anyOptions = true;
		}
		if (actual.codeScanningAllowAdvanced() != null) {
			members.add(
					codeScanningOptions(actual, defaults.codeScanningOptions())
			);
			anyOptions = true;
		}
		if (!actual.secretScanningDelegatedBypassReviewers().isEmpty()) {
			members.add(
					secretScanningDelegatedBypassOptions(
							actual.secretScanningDelegatedBypassReviewers()
					)
			);
			anyOptions = true;
		}
		if (anyOptions) {
			members.add(Fields.note(OPTIONS_NOTE));
		}
		return new PklNode.Field(actual.name(), new PklNode.Obj(members));
	}

	private static PklNode.Member codeScanningDefaultSetupOptions(
			ActualCodeSecurityConfiguration actual,
			Drifty.CodeScanningDefaultSetupOptions base
	) {
		List<PklNode.Member> members = Fields.members(
				Fields.field(
						"runnerType",
						actual.codeScanningRunnerType(),
						base.runnerType.toString()
				),
				Fields.field(
						"runnerLabel",
						actual.codeScanningRunnerLabel(),
						base.runnerLabel
				)
		);
		return new PklNode.Field(
				"codeScanningDefaultSetupOptions",
				new PklNode.Obj(members)
		);
	}

	private static PklNode.Member codeScanningOptions(
			ActualCodeSecurityConfiguration actual,
			Drifty.CodeScanningOptions base
	) {
		List<PklNode.Member> members = Fields.members(
				Fields.field(
						"allowAdvanced",
						actual.codeScanningAllowAdvanced(),
						Boolean.valueOf(base.allowAdvanced)
				)
		);
		return new PklNode.Field(
				"codeScanningOptions",
				new PklNode.Obj(members)
		);
	}

	/**
	 * No schema default exists for a single reviewer's fields, mirroring
	 * {@code RulesetExporter}'s bypass actors: the drift group compares the
	 * whole reviewer as one string, not field by field, so there is nothing to
	 * diff against here either.
	 */
	private static PklNode.Member secretScanningDelegatedBypassOptions(
			Set<BypassReviewer> reviewers
	) {
		List<PklNode> elements = reviewers.stream()
				.sorted(Comparator.comparing(BypassReviewer::toString))
				.<PklNode>map(
						reviewer -> new PklNode.Obj(
								List.of(
										required(
												"reviewerId",
												reviewer.reviewerId()
										),
										required(
												"reviewerType",
												reviewer.reviewerType()
										),
										required("mode", reviewer.mode())
								)
						)
				)
				.toList();
		return new PklNode.Field(
				"secretScanningDelegatedBypassOptions",
				new PklNode.Obj(
						List.of(
								Fields.objects("reviewers", elements)
										.orElseThrow()
						)
				)
		);
	}

	private static PklNode.Member required(String name, String value) {
		return Fields.required(name, value).orElseThrow();
	}

	private static PklNode.Member required(String name, long value) {
		return Fields.required(name, value).orElseThrow();
	}

}
