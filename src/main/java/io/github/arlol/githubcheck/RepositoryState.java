package io.github.arlol.githubcheck;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryFull;
import io.github.arlol.githubcheck.client.RepositoryMinimal;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;

public record RepositoryState(
		String name,
		RepositoryMinimal summary,
		RepositoryFull details,
		boolean vulnerabilityAlerts,
		boolean automatedSecurityFixes,
		Map<String, BranchProtectionResponse> branchProtections,
		List<String> actionSecretNames,
		Map<String, List<String>> environmentSecretNames,
		WorkflowPermissions workflowPermissions,
		List<RulesetDetailsResponse> rulesets,
		Optional<PagesResponse> pages,
		Map<String, EnvironmentDetailsResponse> environmentDetails,
		boolean immutableReleases,
		boolean privateVulnerabilityReporting,
		boolean codeScanningDefaultSetup
) {

	public RepositoryState {
		branchProtections = Map.copyOf(branchProtections);
		actionSecretNames = List.copyOf(actionSecretNames);
		environmentSecretNames = Map.copyOf(environmentSecretNames);
		rulesets = List.copyOf(rulesets);
		environmentDetails = Map.copyOf(environmentDetails);
	}

}
