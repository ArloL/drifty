package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;
import io.github.arlol.githubcheck.config.RepositoryArgs;

class WorkflowPermissionsDriftGroupTest {

	@Test
	void noDriftWhenBothFieldsMatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.defaultWorkflowPermissions(DefaultWorkflowPermissions.WRITE)
				.canApprovePullRequestReviews(true)
				.build();
		var actual = new WorkflowPermissions(
				DefaultWorkflowPermissions.WRITE,
				true
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

	@Test
	void detectsDefaultPermissionsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.defaultWorkflowPermissions(DefaultWorkflowPermissions.WRITE)
				.canApprovePullRequestReviews(false) // match actual to test
													 // only one field
				.build();
		var actual = new WorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("default");
		assertThat(drift.wanted()).isEqualTo(DefaultWorkflowPermissions.WRITE);
		assertThat(drift.got()).isEqualTo(DefaultWorkflowPermissions.READ);
	}

	@Test
	void detectsCanApprovePrsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.canApprovePullRequestReviews(true)
				.defaultWorkflowPermissions(DefaultWorkflowPermissions.READ) // match
																			 // actual
																			 // to
																			 // test
																			 // only
																			 // one
																			 // field
				.build();
		var actual = new WorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("can_approve_prs");
		assertThat(drift.wanted()).isEqualTo(true);
		assertThat(drift.got()).isEqualTo(false);
	}

	@Test
	void detectsBothFieldsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.defaultWorkflowPermissions(DefaultWorkflowPermissions.WRITE)
				.canApprovePullRequestReviews(true)
				.build();
		var actual = new WorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		var group = new WorkflowPermissionsDriftGroup(
				desired,
				actual,
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(2);
	}

}
