package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * A code security configuration as GitHub returns it. {@code target_type} is
 * {@code organization} for the organization's own and {@code global} for the
 * ones GitHub provides. Of the option sub-objects, the code scanning default
 * setup runner and the delegated bypass reviewers are read; the labeled
 * dependency-submission runner and {@code code_scanning_options} are not.
 */
public record CodeSecurityConfigurationResponse(
		long id,
		String targetType,
		String name,
		String description,
		String advancedSecurity,
		String dependencyGraph,
		String dependencyGraphAutosubmitAction,
		String dependabotAlerts,
		String dependabotSecurityUpdates,
		String dependabotDelegatedAlertDismissal,
		String codeScanningDefaultSetup,
		CodeScanningDefaultSetupOptions codeScanningDefaultSetupOptions,
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
