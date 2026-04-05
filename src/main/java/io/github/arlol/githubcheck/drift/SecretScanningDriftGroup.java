package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecurityAndAnalysis;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class SecretScanningDriftGroup extends DriftGroup {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public SecretScanningDriftGroup(
			RepositoryArgs desired,
			boolean actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = desired.secretScanning();
		this.actual = actual;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "secret_scanning";
	}

	@Override
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();
		items.addAll(compare("enabled", desired, actual));
		return items;
	}

	@Override
	public void fix() {
		var sa = SecurityAndAnalysis.builder().secretScanning(desired).build();
		client.updateRepository(
				owner,
				repo,
				io.github.arlol.githubcheck.client.RepositoryUpdateRequest
						.builder()
						.securityAndAnalysis(sa)
						.build()
		);
	}

}
