package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.client.BranchProtectionResponse;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.Desired;

class BranchProtectionDriftGroupTest {

	private static ActualBranchProtection matchingResponse(String branch) {
		return ActualTypes.branchProtection(
				new BranchProtectionResponse(
						null,
						null,
						new BranchProtectionResponse.EnforceAdmins(null, false),
						new BranchProtectionResponse.RequiredLinearHistory(
								false
						),
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
				)
		);
	}

	/**
	 * Builds a response whose review and conversation-resolution blocks the
	 * test controls; everything else matches {@link #matchingResponse}.
	 */
	private static ActualBranchProtection responseWithReviews(
			String branch,
			boolean conversationResolution,
			BranchProtectionResponse.RequiredPullRequestReviews reviews
	) {
		return ActualTypes.branchProtection(
				new BranchProtectionResponse(
						null,
						null,
						new BranchProtectionResponse.EnforceAdmins(null, false),
						new BranchProtectionResponse.RequiredLinearHistory(
								false
						),
						new BranchProtectionResponse.AllowForcePushes(false),
						null,
						null,
						new BranchProtectionResponse.RequiredConversationResolution(
								conversationResolution
						),
						new BranchProtectionResponse.RequiredStatusChecks(
								null,
								null,
								false,
								List.of(),
								null,
								null
						),
						reviews,
						null,
						branch,
						null,
						null,
						null,
						null
				)
		);
	}

	@Test
	void detectsConversationResolutionDrift() {
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withRequireConversationResolution(true)
						)
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("main", responseWithReviews("main", false, null)),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(messages(group)).containsExactly(
				"branch_protection.main.require_conversation_resolution: "
						+ "want=true got=false"
		);
	}

	@Test
	void noDrift_whenApprovingReviewCountMatches() {
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withRequiredApprovingReviewCount(2L)
										.withRequireLastPushApproval(true)
						)
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of(
						"main",
						responseWithReviews(
								"main",
								false,
								new BranchProtectionResponse.RequiredPullRequestReviews(
										null,
										false,
										false,
										2,
										true
								)
						)
				),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsApprovingReviewCountAndLastPushApprovalDrift() {
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withRequiredApprovingReviewCount(2L)
										.withRequireLastPushApproval(true)
						)
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of(
						"main",
						responseWithReviews(
								"main",
								false,
								new BranchProtectionResponse.RequiredPullRequestReviews(
										null,
										false,
										false,
										1,
										false
								)
						)
				),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(messages(group)).contains(
				"branch_protection.main.required_pull_request_reviews."
						+ "required_approving_review_count: want=2 got=1",
				"branch_protection.main.required_pull_request_reviews."
						+ "require_last_push_approval: want=true got=false"
		);
	}

	/**
	 * With nothing desired, GitHub reporting last-push approval as on is drift;
	 * reporting it as off or absent is not.
	 */
	@Test
	void unwantedLastPushApproval_isDriftOnlyWhenEnabled() {
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withDismissStaleReviews(true)
						)
				);

		var enabled = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of(
						"main",
						responseWithReviews(
								"main",
								false,
								new BranchProtectionResponse.RequiredPullRequestReviews(
										null,
										true,
										false,
										null,
										true
								)
						)
				),
				null,
				new RepoRef("owner", "repo")
		);
		assertThat(messages(enabled)).containsExactly(
				"branch_protection.main.required_pull_request_reviews."
						+ "require_last_push_approval: want=null got=true"
		);

		var absent = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of(
						"main",
						responseWithReviews(
								"main",
								false,
								new BranchProtectionResponse.RequiredPullRequestReviews(
										null,
										true,
										false,
										null,
										null
								)
						)
				),
				null,
				new RepoRef("owner", "repo")
		);
		assertThat(absent.detect()).isEmpty();
	}

	private static List<String> messages(BranchProtectionDriftGroup group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.map(DriftItem::message)
				.toList();
	}

	@Test
	void noDrift_whenBothEmpty() {
		var desired = Desired.repository("owner", "repo");
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of(),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingBranchProtection() {
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of("main", Desired.branchProtection())
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of(),
				null,
				new RepoRef("owner", "repo")
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
		var desired = Desired.repository("owner", "repo");
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("main", matchingResponse("main")),
				null,
				new RepoRef("owner", "repo")
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
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of("main", Desired.branchProtection())
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("main", matchingResponse("main")),
				null,
				new RepoRef("owner", "repo")
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsEnforceAdminsDrift() {
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withEnforceAdmins(true)
						)
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("main", matchingResponse("main")),
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
		assertThat(items.getFirst().message()).isEqualTo(
				"branch_protection.main.enforce_admins: want=true got=false"
		);
	}

	@Test
	void detectsMissingStatusCheck() {
		var check = Desired.statusCheck("ci");
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withRequiredStatusChecks(
												List.of(check)
										)
						)
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("main", matchingResponse("main")),
				null,
				new RepoRef("owner", "repo")
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
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withRequiredApprovingReviewCount(1L)
						)
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("main", matchingResponse("main")),
				null,
				new RepoRef("owner", "repo")
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
		var desired = Desired.repository("owner", "repo")
				.withBranchProtections(
						Map.of("main", Desired.branchProtection())
				);
		var group = new BranchProtectionDriftGroup(
				desired.branchProtections,
				Map.of("release", matchingResponse("release")),
				null,
				new RepoRef("owner", "repo")
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
