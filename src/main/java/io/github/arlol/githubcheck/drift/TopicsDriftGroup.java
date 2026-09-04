package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

public class TopicsDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final List<String> desired;
	private final List<String> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public TopicsDriftGroup(
			List<String> desired,
			List<String> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = List.copyOf(desired);
		this.actual = List.copyOf(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.TOPICS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = compare("", desired, actual);
		return List.of(new DriftFix(items, () -> {
			client.replaceTopics(owner, repo, desired);
			return FixResult.success();
		}));
	}

}
