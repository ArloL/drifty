package io.github.arlol.githubcheck.actual;

/**
 * A required status check, as drifty compares them.
 *
 * @param context the check's name
 * @param appId   the GitHub App required to report it, or {@code null} for any
 */
public record StatusCheck(
		String context,
		Integer appId
) {

	@Override
	public String toString() {
		return appId != null ? context + ":" + appId : context;
	}

}
