package io.github.arlol.githubcheck.actual;

import java.util.Optional;

/**
 * A GitHub Pages site as it is configured, reduced to what drifty compares.
 *
 * @param buildType     {@code "workflow"} or {@code "legacy"} in the config's
 *                      spelling, or {@code null} for a site so old that GitHub
 *                      reports no build type at all
 * @param source        the branch and path a legacy build publishes from,
 *                      absent for workflow builds
 * @param httpsEnforced whether GitHub redirects to HTTPS
 */
public record ActualPages(
		String buildType,
		Optional<Source> source,
		boolean httpsEnforced
) {

	public record Source(
			String branch,
			String path
	) {
	}

}
