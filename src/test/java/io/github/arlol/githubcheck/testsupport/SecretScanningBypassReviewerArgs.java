package io.github.arlol.githubcheck.testsupport;

import io.github.arlol.githubcheck.client.SecurityAndAnalysis;

public record SecretScanningBypassReviewerArgs(
		long reviewerId,
		SecurityAndAnalysis.BypassReviewer.ReviewerType reviewerType
) {
}
