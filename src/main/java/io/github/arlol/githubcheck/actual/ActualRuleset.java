package io.github.arlol.githubcheck.actual;

import java.util.List;
import java.util.Set;

/**
 * A repository ruleset as it exists on GitHub, flattened to the settings drifty
 * compares.
 * <p>
 * GitHub returns rulesets as a list of typed rule objects, each with its own
 * nested parameters. Unpicking that shape is the client's job, not the drift
 * comparison's — see {@code ActualTypes}. This record is the vocabulary the
 * comparison works in, so a change to the read path stops at the translator.
 */
public record ActualRuleset(
		long id,
		String name,
		Set<String> includePatterns,
		boolean creation,
		boolean deletion,
		boolean update,
		boolean updateAllowsFetchAndMerge,
		boolean requiredSignatures,
		boolean requiredLinearHistory,
		boolean noForcePushes,
		Set<StatusCheck> requiredStatusChecks,
		Integer requiredReviewCount,
		Set<String> requiredCodeScanningTools,
		Set<String> requiredDeployments,
		String commitMessagePattern,
		String commitAuthorEmailPattern,
		String committerEmailPattern,
		String branchNamePattern,
		String tagNamePattern,
		List<BypassActor> bypassActors
) {

	/**
	 * An actor allowed to bypass the ruleset. Rendered as a single string when
	 * compared, so the comparison does not depend on GitHub's field names.
	 *
	 * @param actorType  role, team, integration or organisation admin
	 * @param actorId    the actor's numeric id
	 * @param bypassMode always, or pull requests only
	 */
	public record BypassActor(
			String actorType,
			Long actorId,
			String bypassMode
	) {

		@Override
		public String toString() {
			return actorType + ":" + actorId + ":" + bypassMode;
		}

	}

	public ActualRuleset {
		includePatterns = Set.copyOf(includePatterns);
		requiredStatusChecks = Set.copyOf(requiredStatusChecks);
		requiredCodeScanningTools = Set.copyOf(requiredCodeScanningTools);
		requiredDeployments = Set.copyOf(requiredDeployments);
		bypassActors = List.copyOf(bypassActors);
	}

}
