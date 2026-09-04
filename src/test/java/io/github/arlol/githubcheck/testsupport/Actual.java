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

}
