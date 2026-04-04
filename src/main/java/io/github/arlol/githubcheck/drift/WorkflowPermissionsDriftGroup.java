package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class WorkflowPermissionsDriftGroup extends DriftGroup {

	private final RepositoryArgs desired;
	private final WorkflowPermissions actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public WorkflowPermissionsDriftGroup(
			RepositoryArgs desired,
			WorkflowPermissions actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "workflow_permissions";
	}

	@Override
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();
		items.addAll(
				compare(
						"default",
						desired.defaultWorkflowPermissions(),
						actual.defaultWorkflowPermissions()
				)
		);
		items.addAll(
				compare(
						"can_approve_prs",
						desired.canApprovePullRequestReviews(),
						actual.canApprovePullRequestReviews()
				)
		);
		return items;
	}

	@Override
	public void fix() {
		client.updateWorkflowPermissions(
				owner,
				repo,
				new WorkflowPermissions(
						desired.defaultWorkflowPermissions(),
						desired.canApprovePullRequestReviews()
				)
		);
	}

}
