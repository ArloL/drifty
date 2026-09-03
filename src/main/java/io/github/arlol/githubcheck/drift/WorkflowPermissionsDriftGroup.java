package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

public class WorkflowPermissionsDriftGroup extends DriftGroup {

	private final Drifty.WorkflowPermissions desiredPermissions;
	private final boolean desiredCanApprove;
	private final ActualWorkflowPermissions actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public WorkflowPermissionsDriftGroup(
			Drifty.WorkflowPermissions desiredPermissions,
			boolean desiredCanApprove,
			ActualWorkflowPermissions actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desiredPermissions = desiredPermissions;
		this.desiredCanApprove = desiredCanApprove;
		this.actual = actual;
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.WORKFLOW_PERMISSIONS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = combine(
				compare(
						"default",
						PklTypes.workflowPermissions(desiredPermissions),
						actual.defaultWorkflowPermissions()
				),
				compare(
						"can_approve_prs",
						desiredCanApprove,
						actual.canApprovePullRequestReviews()
				)
		);
		return List.of(new DriftFix(items, () -> {
			client.updateWorkflowPermissions(
					owner,
					repo,
					new WorkflowPermissions(
							PklTypes.workflowPermissions(desiredPermissions),
							desiredCanApprove
					)
			);
			return FixResult.success();
		}));
	}

}
