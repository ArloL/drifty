package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;

public class TopicsDriftGroup extends DriftGroup {

	private final List<String> desired;
	private final List<String> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public TopicsDriftGroup(
			List<String> desired,
			List<String> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = List.copyOf(desired);
		this.actual = List.copyOf(actual);
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "topics";
	}

	@Override
	public List<DriftFix> detect() {
		var items = compare("topics", desired, actual);
		return List.of(new DriftFix(items, () -> {
			client.replaceTopics(owner, repo, desired);
			return FixResult.success();
		}));
	}

}
