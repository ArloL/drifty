package io.github.arlol.githubcheck;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
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
		boolean codeScanningDefaultSetup
) {

	public RepositoryState {
		branchProtections = Map.copyOf(branchProtections);
		actionSecrets = List.copyOf(actionSecrets);
		environmentSecrets = Map.copyOf(environmentSecrets);
		rulesets = List.copyOf(rulesets);
		environmentDetails = Map.copyOf(environmentDetails);
	}

	// ─── Security and analysis toggles
	// ──────────────────────────────────────
	//
	// These come from the repository response's security_and_analysis block
	// and from nowhere else, so they are read off it rather than stored
	// alongside it. Storing a copy meant "is this flag enabled" had more than
	// one answer: the copy was recorded by fetchState, recomputed by
	// OrgChecker.securityFlag, and inlined a third time in createDriftGroups —
	// and the copies for secret scanning and push protection were written and
	// never read.

	public boolean secretScanning() {
		return enabled(SecurityAndAnalysis::secretScanning);
	}

	public boolean secretScanningPushProtection() {
		return enabled(SecurityAndAnalysis::secretScanningPushProtection);
	}

	public boolean secretScanningNonProviderPatterns() {
		return enabled(SecurityAndAnalysis::secretScanningNonProviderPatterns);
	}

	public boolean secretScanningValidityChecks() {
		return enabled(SecurityAndAnalysis::secretScanningValidityChecks);
	}

	public boolean advancedSecurity() {
		return enabled(SecurityAndAnalysis::advancedSecurity);
	}

	public boolean secretScanningAiDetection() {
		return enabled(SecurityAndAnalysis::secretScanningAiDetection);
	}

	public boolean secretScanningDelegatedAlertDismissal() {
		return enabled(
				SecurityAndAnalysis::secretScanningDelegatedAlertDismissal
		);
	}

	public boolean secretScanningDelegatedBypass() {
		return enabled(SecurityAndAnalysis::secretScanningDelegatedBypass);
	}

	public List<SecurityAndAnalysis.BypassReviewer> bypassReviewers() {
		var sa = securityAndAnalysis();
		if (sa == null || sa.secretScanningDelegatedBypassOptions() == null
				|| sa.secretScanningDelegatedBypassOptions()
						.reviewers() == null) {
			return List.of();
		}
		return sa.secretScanningDelegatedBypassOptions().reviewers();
	}

	private boolean enabled(
			Function<SecurityAndAnalysis, SecurityAndAnalysis.StatusObject> toggle
	) {
		var sa = securityAndAnalysis();
		return sa != null && SecurityAndAnalysis.isEnabled(toggle.apply(sa));
	}

	private SecurityAndAnalysis securityAndAnalysis() {
		return details == null ? null : details.securityAndAnalysis();
	}

}
