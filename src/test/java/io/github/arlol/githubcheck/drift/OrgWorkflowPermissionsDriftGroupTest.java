package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

class OrgWorkflowPermissionsDriftGroupTest {

	@Test
	void noDriftWhenBothFieldsMatch() {
		var desired = Desired.organization();
		var actual = new ActualWorkflowPermissions(
				WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
				true
		);
		var group = new OrgWorkflowPermissionsDriftGroup(
				desired.defaultWorkflowPermissions,
				desired.canApprovePullRequestReviews,
				actual,
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items).isEmpty();
	}

	@Test
	void detectsDefaultPermissionsDrift() {
		var group = new OrgWorkflowPermissionsDriftGroup(
				Drifty.WorkflowPermissions.READ,
				true,
				new ActualWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
						true
				),
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_workflow_permissions.default_workflow_permissions"
				);
	}

	@Test
	void detectsCanApproveDrift() {
		var group = new OrgWorkflowPermissionsDriftGroup(
				Drifty.WorkflowPermissions.WRITE,
				false,
				new ActualWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
						true
				),
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_workflow_permissions.can_approve_pull_request_reviews"
				);
	}

	@Test
	void name() {
		var group = new OrgWorkflowPermissionsDriftGroup(
				Drifty.WorkflowPermissions.WRITE,
				true,
				new ActualWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
						true
				),
				null,
				"my-org"
		);

		assertThat(group.name())
				.isEqualTo(Drifty.OrgGroupName.ORG_WORKFLOW_PERMISSIONS);
	}

}
