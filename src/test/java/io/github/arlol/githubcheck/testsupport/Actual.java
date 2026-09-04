package io.github.arlol.githubcheck.testsupport;

import io.github.arlol.githubcheck.actual.ActualOrganization;

/**
 * Actual-state fixtures: what GitHub returns for a freshly created entity, in
 * drifty's own vocabulary.
 * <p>
 * The counterpart of {@link Desired}. Both sides start from the same defaults,
 * so a test that changes nothing detects no drift and a test that changes one
 * field detects exactly that one.
 */
public final class Actual {

	private Actual() {
	}

	/** An organization with GitHub's defaults for a new organization. */
	public static ActualOrganization organization() {
		return new ActualOrganization(
				"",
				"",
				"",
				"",
				"",
				"",
				"",
				true,
				true,
				"read",
				true,
				true,
				true,
				false,
				true,
				true,
				true,
				false,
				false,
				false,
				"main",
				false,
				true,
				true,
				true,
				false,
				true,
				true,
				false,
				false
		);
	}

	/**
	 * An organization whose every managed setting differs from
	 * {@link Desired#organization()}: each string is another string, each flag
	 * is inverted. It sits beside {@link #organization()} rather than in the
	 * one test that drifts everything, because the two literals encode the same
	 * thirty-field order positionally — kept apart, swapping two booleans in
	 * one of them compiles and passes.
	 */
	public static ActualOrganization driftedOrganization() {
		return new ActualOrganization(
				"stale",
				"stale",
				"stale",
				"stale",
				"stale",
				"stale",
				"stale",
				false,
				false,
				"admin",
				false,
				false,
				false,
				true,
				false,
				false,
				false,
				true,
				true,
				true,
				"master",
				true,
				false,
				false,
				false,
				true,
				false,
				false,
				true,
				true
		);
	}

}
