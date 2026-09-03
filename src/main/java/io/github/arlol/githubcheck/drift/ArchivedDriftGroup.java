package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

public class ArchivedDriftGroup extends DriftGroup {

	private final boolean desiredArchived;
	private final boolean actualArchived;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ArchivedDriftGroup(
			boolean desiredArchived,
			boolean actualArchived,
			GitHubClient client,
			RepoRef ref
	) {
		this.desiredArchived = desiredArchived;
		this.actualArchived = actualArchived;
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.ARCHIVED;
	}

	@Override
	public boolean runsBeforeOtherFixes() {
		return true;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = compare("", desiredArchived, actualArchived);
		return List.of(new DriftFix(items, () -> {
			client.updateRepository(
					owner,
					repo,
					RepositoryUpdateRequest.builder()
							.archived(desiredArchived)
							.build()
			);
			return FixResult.success();
		}));
	}

}
