package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The organization's default {@code GITHUB_TOKEN} permissions for workflows —
 * the org twin of {@link WorkflowPermissionsDriftGroup}, on
 * {@code /orgs/{org}/actions/permissions/workflow}. The response is the same
 * {@link WorkflowPermissions} record as the repository endpoint, so no new
 * client or actual type is needed here.
 */
public class OrgWorkflowPermissionsDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	private final Drifty.WorkflowPermissions desiredPermissions;
	private final boolean desiredCanApprove;
	private final ActualWorkflowPermissions actual;
	private final GitHubClient client;
	private final String org;

	public OrgWorkflowPermissionsDriftGroup(
			Drifty.WorkflowPermissions desiredPermissions,
			boolean desiredCanApprove,
			ActualWorkflowPermissions actual,
			GitHubClient client,
			String org
	) {
		this.desiredPermissions = desiredPermissions;
		this.desiredCanApprove = desiredCanApprove;
		this.actual = actual;
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_WORKFLOW_PERMISSIONS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = combine(
				compare(
						"default_workflow_permissions",
						PklTypes.workflowPermissions(desiredPermissions),
						actual.defaultWorkflowPermissions()
				),
				compare(
						"can_approve_pull_request_reviews",
						desiredCanApprove,
						actual.canApprovePullRequestReviews()
				)
		);
		return List.of(new DriftFix(items, () -> {
			client.updateOrgWorkflowPermissions(
					org,
					new WorkflowPermissions(
							PklTypes.workflowPermissions(desiredPermissions),
							desiredCanApprove
					)
			);
			return FixResult.success();
		}));
	}

}
