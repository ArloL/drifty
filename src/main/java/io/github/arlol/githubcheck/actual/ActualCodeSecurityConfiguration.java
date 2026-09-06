package io.github.arlol.githubcheck.actual;

import java.util.Map;
import java.util.Set;

/**
 * A code security configuration on an organization. The security settings sit
 * in one map keyed by GitHub's field name, since all seventeen are the same
 * three-valued toggle and the drift group compares them from a table; the
 * default-for-new-repositories value and the attached repositories come from
 * their own requests. The description is {@code ""} when GitHub has none.
 * <p>
 * The two option sub-objects arrive flattened: the code scanning runner is
 * {@code not_set} with a null label when GitHub has no options object, and the
 * bypass reviewers are an empty set when it has none.
 */
public record ActualCodeSecurityConfiguration(
		long id,
		String name,
		String description,
		Map<String, String> settings,
		String enforcement,
		String codeScanningRunnerType,
		String codeScanningRunnerLabel,
		Set<BypassReviewer> secretScanningDelegatedBypassReviewers,
		String defaultForNewRepos,
		Set<String> repositories
) {

	/**
	 * A secret scanning delegated bypass reviewer. Rendered as
	 * {@code <type>:<id>:<mode>} when compared.
	 */
	public record BypassReviewer(
			String reviewerType,
			long reviewerId,
			String mode
	) {

		@Override
		public String toString() {
			return reviewerType + ":" + reviewerId + ":" + mode;
		}

	}

	public ActualCodeSecurityConfiguration {
		settings = Map.copyOf(settings);
		secretScanningDelegatedBypassReviewers = Set
				.copyOf(secretScanningDelegatedBypassReviewers);
		repositories = Set.copyOf(repositories);
	}

}
