package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class CodeScanningDefaultSetupDriftGroup extends DriftGroup {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public CodeScanningDefaultSetupDriftGroup(
			RepositoryArgs desired,
			boolean actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = desired.codeScanningDefaultSetup();
		this.actual = actual;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "code_scanning_default_setup";
	}

	@Override
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();
		items.addAll(compare("enabled", desired, actual));
		return items;
	}

	@Override
	public void fix() {
		if (desired) {
			client.enableCodeScanningDefaultSetup(owner, repo);
		} else {
			client.disableCodeScanningDefaultSetup(owner, repo);
		}
	}

}
