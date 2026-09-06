package io.github.arlol.githubcheck.actual;

import java.util.List;
import java.util.Set;

/**
 * A deployment environment's protection settings, as drifty compares them.
 * <p>
 * GitHub returns these as a list of typed protection rules plus an optional
 * branch policy object, and leaves both out when nothing is configured; the
 * branch name patterns behind a custom policy come from a separate listing.
 * Flattening that is the client's business, see {@code ActualTypes}.
 *
 * @param waitTimer            minutes to wait before deploying; {@code 0} when
 *                             no wait timer rule is set
 * @param preventSelfReview    whether a deployer may not approve their own
 *                             deployment
 * @param reviewers            the required reviewers as {@code User:<login>}
 *                             and {@code Team:<slug>}
 * @param protectedBranches    whether only protected branches may deploy
 * @param customBranchPolicies whether custom branch policies are in force
 * @param branchPolicies       the custom policies, empty unless
 *                             {@code customBranchPolicies}
 */
public record ActualEnvironment(
		int waitTimer,
		boolean preventSelfReview,
		Set<String> reviewers,
		boolean protectedBranches,
		boolean customBranchPolicies,
		List<BranchPolicy> branchPolicies
) {

	/**
	 * One deployment branch policy. Rendered as {@code <type>:<name>} when
	 * compared; the id is what the delete endpoint wants.
	 */
	public record BranchPolicy(
			long id,
			String type,
			String name
	) {

		@Override
		public String toString() {
			return type + ":" + name;
		}

	}

	public ActualEnvironment {
		reviewers = Set.copyOf(reviewers);
		branchPolicies = List.copyOf(branchPolicies);
	}

	/** An environment with no reviewers and no custom policies. */
	public ActualEnvironment(
			int waitTimer,
			boolean protectedBranches,
			boolean customBranchPolicies
	) {
		this(
				waitTimer,
				false,
				Set.of(),
				protectedBranches,
				customBranchPolicies,
				List.of()
		);
	}

}
