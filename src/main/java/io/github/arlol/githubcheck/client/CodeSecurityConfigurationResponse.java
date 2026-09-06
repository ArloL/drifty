package io.github.arlol.githubcheck.client;

/**
 * A code security configuration as GitHub returns it. {@code target_type} is
 * {@code organization} for the organization's own and {@code global} for the
 * ones GitHub provides. The option sub-objects (labeled runners, bypass
 * reviewers) are not read.
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
		String codeScanningDelegatedAlertDismissal,
		String secretScanning,
		String secretScanningPushProtection,
		String secretScanningDelegatedBypass,
		String secretScanningValidityChecks,
		String secretScanningNonProviderPatterns,
		String secretScanningGenericSecrets,
		String secretScanningDelegatedAlertDismissal,
		String privateVulnerabilityReporting,
		String enforcement,
		String updatedAt
) {
}
