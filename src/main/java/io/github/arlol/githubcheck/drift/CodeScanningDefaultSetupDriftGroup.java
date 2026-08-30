package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;

public class CodeScanningDefaultSetupDriftGroup extends DriftGroup {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public CodeScanningDefaultSetupDriftGroup(
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
		return "code_scanning_default_setup";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = compare("enabled", desired, actual);
		return List.of(new DriftFix(items, () -> {
			if (desired) {
				client.enableCodeScanningDefaultSetup(owner, repo);
			} else {
				client.disableCodeScanningDefaultSetup(owner, repo);
			}
			return FixResult.success();
		}));
	}

}
