package io.github.arlol.githubcheck;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.client.WorkflowPermissions;

public record RepositoryState(
		String name,
		RepositorySummaryResponse summary,
		RepositoryDetailsResponse details,
		boolean vulnerabilityAlerts,
		boolean automatedSecurityFixes,
		Map<String, BranchProtectionResponse> branchProtections,
		List<Secret> actionSecrets,
		Map<String, List<Secret>> environmentSecrets,
		WorkflowPermissions workflowPermissions,
		List<RulesetDetailsResponse> rulesets,
		Optional<PagesResponse> pages,
		Map<String, EnvironmentDetailsResponse> environmentDetails,
		boolean immutableReleases,
		boolean privateVulnerabilityReporting,
		boolean codeScanningDefaultSetup,
		boolean secretScanning,
		boolean secretScanningPushProtection,
		boolean secretScanningNonProviderPatterns,
		boolean secretScanningValidityChecks
) {

	public RepositoryState {
		branchProtections = Map.copyOf(branchProtections);
		actionSecrets = List.copyOf(actionSecrets);
		environmentSecrets = Map.copyOf(environmentSecrets);
		rulesets = List.copyOf(rulesets);
		environmentDetails = Map.copyOf(environmentDetails);
	}

	public RepositoryState(
			String name,
			RepositorySummaryResponse summary,
			RepositoryDetailsResponse details,
			boolean vulnerabilityAlerts,
			boolean automatedSecurityFixes,
			Map<String, BranchProtectionResponse> branchProtections,
			List<Secret> actionSecrets,
			Map<String, List<Secret>> environmentSecrets,
			WorkflowPermissions workflowPermissions,
			List<RulesetDetailsResponse> rulesets,
			Optional<PagesResponse> pages,
			Map<String, EnvironmentDetailsResponse> environmentDetails,
			boolean immutableReleases,
			boolean privateVulnerabilityReporting,
			boolean codeScanningDefaultSetup
	) {
		this(
				name,
				summary,
				details,
				vulnerabilityAlerts,
				automatedSecurityFixes,
				branchProtections,
				actionSecrets,
				environmentSecrets,
				workflowPermissions,
				rulesets,
				pages,
				environmentDetails,
				immutableReleases,
				privateVulnerabilityReporting,
				codeScanningDefaultSetup,
				false,
				false,
				false,
				false
		);
	}

}
