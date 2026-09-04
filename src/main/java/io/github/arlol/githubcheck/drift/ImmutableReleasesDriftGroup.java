package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

public class ImmutableReleasesDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final boolean desired;
	private final boolean actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ImmutableReleasesDriftGroup(
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
	public Drifty.GroupName name() {
		return Drifty.GroupName.IMMUTABLE_RELEASES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
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
