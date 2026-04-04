package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
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
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();
		items.addAll(
				compareSets(
						"topics",
						new HashSet<>(desired),
						new HashSet<>(actual)
				)
		);
		return items;
	}

	@Override
	public void fix() {
		client.replaceTopics(owner, repo, desired);
	}

}
