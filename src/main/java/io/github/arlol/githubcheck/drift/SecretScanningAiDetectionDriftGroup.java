package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.pkl.Drifty;

public class SecretScanningAiDetectionDriftGroup extends DriftGroup {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public SecretScanningAiDetectionDriftGroup(
			Drifty.Repository desired,
			boolean actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = desired.secretScanningAiDetection;
		this.actual = actual;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "secret_scanning_ai_detection";
	}

	@Override
	public List<DriftFix> detect() {
		var items = compare("enabled", desired, actual);
		return List.of(new DriftFix(items, () -> {
			var sa = SecurityAndAnalysis.builder()
					.secretScanningAiDetection(desired)
					.build();
			client.updateRepository(
					owner,
					repo,
					RepositoryUpdateRequest.builder()
							.securityAndAnalysis(sa)
							.build()
			);
			return FixResult.success();
		}));
	}

}
