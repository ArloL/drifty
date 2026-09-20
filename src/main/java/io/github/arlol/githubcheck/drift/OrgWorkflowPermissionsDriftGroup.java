package io.github.arlol.githubcheck.drift;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

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
	private final @Nullable ActualWorkflowPermissions actual;
	private final GitHubClient client;
	private final String org;

	public OrgWorkflowPermissionsDriftGroup(
			Drifty.WorkflowPermissions desiredPermissions,
			boolean desiredCanApprove,
			@Nullable ActualWorkflowPermissions actual,
			GitHubClient client,
			String org
	) {
		this.desiredPermissions = desiredPermissions;
		this.desiredCanApprove = desiredCanApprove;
		this.actual = actual;
		this.client = client;
		this.org = org;
	}

	/**
	 * The section this group compares. Null only for a group the config does
	 * not manage, whose read was therefore never sent — and an unmanaged group
	 * is filtered out before {@code detectDrift} runs, in
	 * {@code OrganizationChecker.createDriftGroups}. Saying so here turns a
	 * would-be NullPointerException into a named invariant.
	 */
	private ActualWorkflowPermissions present() {
		return Objects.requireNonNull(actual);
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
						present().defaultWorkflowPermissions()
				),
				compare(
						"can_approve_pull_request_reviews",
						desiredCanApprove,
						present().canApprovePullRequestReviews()
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
