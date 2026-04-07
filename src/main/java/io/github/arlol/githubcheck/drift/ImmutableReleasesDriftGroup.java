package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class ImmutableReleasesDriftGroup extends DriftGroup {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ImmutableReleasesDriftGroup(
			RepositoryArgs desired,
			boolean actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = desired.immutableReleases();
		this.actual = actual;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "immutable_releases";
	}

	@Override
	public List<DriftFix> detect() {
		var items = compare("enabled", desired, actual);
		return List.of(new DriftFix(items, () -> {
			if (desired) {
				client.enableImmutableReleases(owner, repo);
			} else {
				client.disableImmutableReleases(owner, repo);
			}
			return FixResult.success();
		}));
	}

}
