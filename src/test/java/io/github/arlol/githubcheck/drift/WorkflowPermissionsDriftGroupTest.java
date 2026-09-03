package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

class WorkflowPermissionsDriftGroupTest {

	@Test
	void noDriftWhenBothFieldsMatch() {
		var desired = Desired.repository("owner", "repo")
				.withDefaultWorkflowPermissions(
						Drifty.WorkflowPermissions.WRITE
				)
				.withCanApprovePullRequestReviews(true);
		var actual = new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.WRITE,
				true
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired.defaultWorkflowPermissions,
				desired.canApprovePullRequestReviews,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

	@Test
	void detectsDefaultPermissionsDrift() {
		// canApprove matches actual to test only one field
		var desired = Desired.repository("owner", "repo")
				.withDefaultWorkflowPermissions(
						Drifty.WorkflowPermissions.WRITE
				)
				.withCanApprovePullRequestReviews(false);
		var actual = new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired.defaultWorkflowPermissions,
				desired.canApprovePullRequestReviews,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("workflow_permissions.default");
		assertThat(drift.wanted()).isEqualTo(DefaultWorkflowPermissions.WRITE);
		assertThat(drift.got()).isEqualTo(DefaultWorkflowPermissions.READ);
	}

	@Test
	void detectsCanApprovePrsDrift() {
		// defaultWorkflowPermissions matches actual to test only one field
		var desired = Desired.repository("owner", "repo")
				.withCanApprovePullRequestReviews(true)
				.withDefaultWorkflowPermissions(
						Drifty.WorkflowPermissions.READ
				);
		var actual = new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired.defaultWorkflowPermissions,
				desired.canApprovePullRequestReviews,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path())
				.isEqualTo("workflow_permissions.can_approve_prs");
		assertThat(drift.wanted()).isEqualTo(true);
		assertThat(drift.got()).isEqualTo(false);
	}

	@Test
	void detectsBothFieldsDrift() {
		var desired = Desired.repository("owner", "repo")
				.withDefaultWorkflowPermissions(
						Drifty.WorkflowPermissions.WRITE
				)
				.withCanApprovePullRequestReviews(true);
		var actual = new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired.defaultWorkflowPermissions,
				desired.canApprovePullRequestReviews,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(2);
	}

}
