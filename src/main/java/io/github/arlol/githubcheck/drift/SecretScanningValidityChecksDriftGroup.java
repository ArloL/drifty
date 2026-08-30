package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;

public class SecretScanningValidityChecksDriftGroup extends DriftGroup {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public SecretScanningValidityChecksDriftGroup(
			boolean desired,
			boolean actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public String name() {
		return "secret_scanning_validity_checks";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = compare("enabled", desired, actual);
		return List.of(new DriftFix(items, () -> {
			var sa = SecurityAndAnalysis.builder()
					.secretScanningValidityChecks(desired)
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
