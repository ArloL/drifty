package io.github.arlol.githubcheck.actual;

import java.util.List;

/**
 * The security toggles GitHub reports on the repository itself, flattened to on
 * or off.
 * <p>
 * On the wire each toggle is a {@code {"status": "enabled"}} object, GitHub
 * omits individual toggles for some repositories and the whole block for
 * others, and both mean off. Deciding that is the client's business, see
 * {@code ActualTypes}; here a toggle is a boolean and there is exactly one
 * answer to "is it on".
 * <p>
 * The security features GitHub serves from their own endpoints — vulnerability
 * alerts, automated security fixes, immutable releases, private vulnerability
 * reporting, code scanning default setup — are not here: they arrive as plain
 * booleans already and sit on {@code RepositoryState} directly.
 */
public record ActualSecurityAndAnalysis(
		boolean secretScanning,
		boolean secretScanningPushProtection,
		boolean secretScanningNonProviderPatterns,
		boolean secretScanningValidityChecks,
		boolean advancedSecurity,
		boolean secretScanningAiDetection,
		boolean secretScanningDelegatedAlertDismissal,
		boolean secretScanningDelegatedBypass,
		List<BypassReviewer> bypassReviewers
) {

	/**
	 * A reviewer allowed to approve a delegated secret-scanning bypass.
	 *
	 * @param reviewerType {@code TEAM} or {@code ROLE}
	 * @param reviewerId   the team's or role's numeric id
	 */
	public record BypassReviewer(
			String reviewerType,
			long reviewerId
	) {

		@Override
		public String toString() {
			return reviewerType + ":" + reviewerId;
		}

	}

	public ActualSecurityAndAnalysis {
		bypassReviewers = List.copyOf(bypassReviewers);
	}

}
