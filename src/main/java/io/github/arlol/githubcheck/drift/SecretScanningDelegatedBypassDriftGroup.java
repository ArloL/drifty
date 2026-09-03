package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.pkl.Drifty;

public class SecretScanningDelegatedBypassDriftGroup extends DriftGroup {

	private final boolean desiredEnabled;
	private final boolean actualEnabled;
	private final List<Drifty.SecretScanningBypassReviewer> desiredReviewers;
	private final List<ActualSecurityAndAnalysis.BypassReviewer> actualReviewers;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public SecretScanningDelegatedBypassDriftGroup(
			boolean desiredEnabled,
			List<Drifty.SecretScanningBypassReviewer> desiredReviewers,
			boolean actualEnabled,
			List<ActualSecurityAndAnalysis.BypassReviewer> actualReviewers,
			GitHubClient client,
			RepoRef ref
	) {
		this.desiredEnabled = desiredEnabled;
		this.actualEnabled = actualEnabled;
		this.desiredReviewers = List.copyOf(desiredReviewers);
		this.actualReviewers = List.copyOf(actualReviewers);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public String name() {
		return "secret_scanning_delegated_bypass";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = new ArrayList<DriftItem>(
				compare("enabled", desiredEnabled, actualEnabled)
		);
		// Reviewers are only honored when delegated bypass is enabled.
		if (desiredEnabled) {
			Set<String> wanted = desiredReviewers.stream()
					.map(r -> key(r.reviewerType.name(), r.reviewerId))
					.collect(Collectors.toSet());
			Set<String> got = actualReviewers.stream()
					.map(r -> key(r.reviewerType(), r.reviewerId()))
					.collect(Collectors.toSet());
			items.addAll(compare("reviewers", wanted, got));
		}
		return List.of(new DriftFix(items, () -> {
			var saBuilder = SecurityAndAnalysis.builder()
					.secretScanningDelegatedBypass(desiredEnabled);
			if (desiredEnabled) {
				saBuilder.secretScanningDelegatedBypassReviewers(
						desiredReviewers.stream()
								.map(
										r -> new SecurityAndAnalysis.BypassReviewer(
												r.reviewerId,
												PklTypes.reviewerType(
														r.reviewerType
												)
										)
								)
								.toList()
				);
			}
			client.updateRepository(
					owner,
					repo,
					RepositoryUpdateRequest.builder()
							.securityAndAnalysis(saBuilder.build())
							.build()
			);
			return FixResult.success();
		}));
	}

	private static String key(String reviewerType, long reviewerId) {
		return reviewerType + ":" + reviewerId;
	}

}
