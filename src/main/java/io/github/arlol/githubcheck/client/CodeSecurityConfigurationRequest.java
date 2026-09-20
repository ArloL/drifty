package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * Body of the code security configuration POST and PATCH. The same shape serves
 * both: the config object is the whole desired state, so every field is sent
 * either way. The nullable option objects are the exception: a config that
 * leaves one out says nothing about it, and it is omitted so GitHub keeps
 * whatever it has. {@code dependency_graph_autosubmit_action_options} is not
 * one of them — GitHub returns it on every configuration, so it is an ordinary
 * field and always sent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = { "POST /orgs/{org}/code-security/configurations",
				"PATCH /orgs/{org}/code-security/configurations/{configuration_id}" },
		unmanaged = {
				"code_security — GitHub's repackaging of Advanced Security; drifty compares advanced_security, which is the same switch under the name the rest of the API still uses",
				"secret_protection — the same repackaging, and secret_scanning is what drifty compares",
				"secret_scanning_extended_metadata — the spec supplies no default and every example predates the field, so any value drifty picked would report drift on configurations nobody has touched. FOLLOWUPS.md's extended-metadata carries the one read that settles it" }
)
public record CodeSecurityConfigurationRequest(
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
		@Nullable CodeScanningDefaultSetupOptions codeScanningDefaultSetupOptions,
		@Nullable CodeScanningOptions codeScanningOptions,
		String codeScanningDelegatedAlertDismissal,
		String secretScanning,
		String secretScanningPushProtection,
		String secretScanningDelegatedBypass,
		@Nullable SecretScanningDelegatedBypassOptions secretScanningDelegatedBypassOptions,
		String secretScanningValidityChecks,
		String secretScanningNonProviderPatterns,
		String secretScanningGenericSecrets,
		String secretScanningDelegatedAlertDismissal,
		String privateVulnerabilityReporting,
		String enforcement
) {

	/** {@code dependency_graph_autosubmit_action_options}. */
	public record DependencyGraphAutosubmitActionOptions(
			boolean labeledRunners
	) {
	}

	/** {@code code_scanning_options}, sent only when the config sets it. */
	public record CodeScanningOptions(
			boolean allowAdvanced
	) {
	}

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
