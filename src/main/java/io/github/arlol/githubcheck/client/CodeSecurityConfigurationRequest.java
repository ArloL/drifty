package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the code security configuration POST and PATCH. The same shape serves
 * both: the config object is the whole desired state, so every field is sent
 * either way. The two option objects are the exception: a config that leaves
 * them out says nothing about them, and they are omitted so GitHub keeps
 * whatever it has.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeSecurityConfigurationRequest(
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
		String enforcement
) {

	/**
	 * {@code runner_label} is sent as null when the runner type is not
	 * {@code labeled}, which is how the label is cleared.
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record CodeScanningDefaultSetupOptions(
			String runnerType,
			String runnerLabel
	) {
	}

	public record SecretScanningDelegatedBypassOptions(
			List<BypassReviewer> reviewers
	) {

		public SecretScanningDelegatedBypassOptions {
			reviewers = reviewers == null ? null : List.copyOf(reviewers);
		}

	}

	public record BypassReviewer(
			long reviewerId,
			String reviewerType,
			String mode
	) {
	}

}
