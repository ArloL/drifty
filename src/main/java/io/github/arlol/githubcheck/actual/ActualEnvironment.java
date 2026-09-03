package io.github.arlol.githubcheck.actual;

/**
 * A deployment environment's protection settings, as drifty compares them.
 * <p>
 * GitHub returns these as a list of typed protection rules plus an optional
 * branch policy object, and leaves both out when nothing is configured.
 * Flattening that is the client's business, see {@code ActualTypes}.
 *
 * @param waitTimer            minutes to wait before deploying; {@code 0} when
 *                             no wait timer rule is set
 * @param protectedBranches    whether only protected branches may deploy
 * @param customBranchPolicies whether custom branch policies are in force
 */
public record ActualEnvironment(
		int waitTimer,
		boolean protectedBranches,
		boolean customBranchPolicies
) {
}
