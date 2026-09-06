package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the code security configuration POST and PATCH. The same shape serves
 * both: the config object is the whole desired state, so every field is sent
 * either way.
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
		String codeScanningDelegatedAlertDismissal,
		String secretScanning,
		String secretScanningPushProtection,
		String secretScanningDelegatedBypass,
		String secretScanningValidityChecks,
		String secretScanningNonProviderPatterns,
		String secretScanningGenericSecrets,
		String secretScanningDelegatedAlertDismissal,
		String privateVulnerabilityReporting,
		String enforcement
) {
}
