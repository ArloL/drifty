package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * A code security configuration as GitHub returns it. {@code target_type} is
 * {@code organization} for the organization's own and {@code global} for the
 * ones GitHub provides. Of the option sub-objects, the code scanning default
 * setup runner, the delegated bypass reviewers, the labeled
 * dependency-submission runner and {@code code_scanning_options} are all read.
 */
public record CodeSecurityConfigurationResponse(
		long id,
		String targetType,
		String name,
		String description,
		String advancedSecurity,
		String dependencyGraph,
		String dependencyGraphAutosubmitAction,
		DependencyGraphAutosubmitActionOptions dependencyGraphAutosubmitActionOptions,
		String dependabotAlerts,
		String dependabotSecurityUpdates,
		String dependabotDelegatedAlertDismissal,
		String codeScanningDefaultSetup,
		CodeScanningDefaultSetupOptions codeScanningDefaultSetupOptions,
		CodeScanningOptions codeScanningOptions,
		String codeScanningDelegatedAlertDismissal,
		String secretScanning,
		String secretScanningPushProtection,
		String secretScanningDelegatedBypass,
		SecretScanningDelegatedBypassOptions secretScanningDelegatedBypassOptions,
		String secretScanningValidityChecks,
		String secretScanningNonProviderPatterns,
		String secretScanningGenericSecrets,
		String secretScanningDelegatedAlertDismissal,
		String privateVulnerabilityReporting,
		String enforcement,
		String updatedAt
) {

	/**
	 * {@code dependency_graph_autosubmit_action_options}. GitHub returns this
	 * object on every configuration, a configuration that never set it
	 * included, so a null here means an older response and reads as false.
	 */
	public record DependencyGraphAutosubmitActionOptions(
			Boolean labeledRunners
	) {
	}

	/**
	 * {@code code_scanning_options}. GitHub omits the object on a configuration
	 * that never set it, and answers {@code allow_advanced} as null within it;
	 * both mean no value, unlike the runner options above.
	 */
	public record CodeScanningOptions(
			Boolean allowAdvanced
	) {
	}

	/**
	 * {@code code_scanning_default_setup_options}. GitHub answers the whole
	 * object as null, or {@code runner_type} as {@code not_set} with a null
	 * label, when no runner has been chosen.
	 */
	public record CodeScanningDefaultSetupOptions(
			String runnerType,
			String runnerLabel
	) {
	}

	/** {@code secret_scanning_delegated_bypass_options}. */
	public record SecretScanningDelegatedBypassOptions(
			List<BypassReviewer> reviewers
	) {

		public SecretScanningDelegatedBypassOptions {
			reviewers = reviewers == null ? null : List.copyOf(reviewers);
		}

	}

	/**
	 * One bypass reviewer. {@code mode} is absent on configurations created
	 * before GitHub added it; the schema's default is {@code ALWAYS}.
	 */
	public record BypassReviewer(
			long reviewerId,
			String reviewerType,
			String mode
	) {
	}

}
