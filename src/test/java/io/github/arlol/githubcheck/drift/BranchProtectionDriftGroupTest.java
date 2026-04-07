package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.config.BranchProtectionArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

class BranchProtectionDriftGroupTest {

	private static BranchProtectionResponse matchingResponse(String branch) {
		return new BranchProtectionResponse(
				null,
				null,
				new BranchProtectionResponse.EnforceAdmins(null, false),
				new BranchProtectionResponse.RequiredLinearHistory(false),
				new BranchProtectionResponse.AllowForcePushes(false),
				null,
				null,
				null,
				new BranchProtectionResponse.RequiredStatusChecks(
						null,
						null,
						false,
						List.of(),
						null,
						null
				),
				null,
				null,
				branch,
				null,
				null,
				null,
				null
		);
	}

	@Test
	void noDrift_whenBothEmpty() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of(),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingBranchProtection() {
		var desired = RepositoryArgs.create("owner", "repo")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of(),
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
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message())
				.isEqualTo("branch_protection.main: missing");
	}

	@Test
	void detectsExtraBranchProtection() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of("main", matchingResponse("main")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().message())
				.isEqualTo("branch_protection.main: extra (should not exist)");
	}

	@Test
	void noDrift_whenBranchProtectionMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of("main", matchingResponse("main")),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsEnforceAdminsDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.branchProtections(
						BranchProtectionArgs.builder("main")
								.enforceAdmins(true)
								.build()
				)
				.build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of("main", matchingResponse("main")),
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
		assertThat(items.getFirst().message()).isEqualTo(
				"branch_protection.main.enforce_admins: want=true got=false"
		);
	}

	@Test
	void detectsMissingStatusCheck() {
		var check = StatusCheckArgs.builder().context("ci").build();
		var desired = RepositoryArgs.create("owner", "repo")
				.branchProtections(
						BranchProtectionArgs.builder("main")
								.requiredStatusChecks(check)
								.build()
				)
				.build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of("main", matchingResponse("main")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.path())
				.isEqualTo("branch_protection.main.required_status_checks");
		assertThat(drift.missing()).hasSize(1);
		assertThat(drift.extra()).isEmpty();
	}

	@Test
	void detectsMissingPullRequestReviews() {
		var desired = RepositoryArgs.create("owner", "repo")
				.branchProtections(
						BranchProtectionArgs.builder("main")
								.requiredApprovingReviewCount(1)
								.build()
				)
				.build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of("main", matchingResponse("main")),
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
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"branch_protection.main.required_pull_request_reviews: missing"
		);
	}

	@Test
	void detectsMissingBranchAndExtraBranch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();
		var group = new BranchProtectionDriftGroup(
				desired,
				Map.of("release", matchingResponse("release")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(i -> i instanceof DriftItem.SectionMissing);
		assertThat(items).anyMatch(i -> i instanceof DriftItem.SectionExtra);
	}

}
