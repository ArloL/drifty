package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.StatusCheck;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A branch protection rule, as the config lines that differ from the schema's
 * defaults.
 * <p>
 * The schema is flat: {@code pullRequestReviews} and {@code restrictions} are
 * plain fields of {@code BranchProtection}, not nested objects, while
 * {@link ActualBranchProtection} keeps GitHub's own nesting with both sections
 * {@code Optional} — {@code BranchProtectionDriftGroup} tells an absent section
 * apart from an empty one, reporting a whole section missing rather than
 * comparing field by field. Every field inside either section already defaults
 * to "not configured" (false, null, or an empty listing), which is exactly what
 * an absent section means, so substituting that default in for a missing
 * {@code Optional} reaches the same "nothing to compare" outcome without a
 * second code path — and without ever writing something for a section GitHub
 * does not have.
 */
public final class BranchProtectionExporter {

	/** What an absent {@code pullRequestReviews} means: nothing configured. */
	private static final ActualBranchProtection.PullRequestReviews NO_PULL_REQUEST_REVIEWS = new ActualBranchProtection.PullRequestReviews(
			false,
			false,
			null,
			null,
			ActualBranchProtection.Actors.NONE,
			ActualBranchProtection.Actors.NONE
	);

	/** What an absent {@code restrictions} means: nobody is restricted. */
	private static final ActualBranchProtection.Restrictions NO_RESTRICTIONS = new ActualBranchProtection.Restrictions(
			Set.of(),
			Set.of(),
			Set.of()
	);

	private BranchProtectionExporter() {
	}

	public static PklNode.Member entry(
			String pattern,
			ActualBranchProtection actual,
			Drifty.BranchProtection base
	) {
		var members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"enforceAdmins",
								actual.enforceAdmins(),
								base.enforceAdmins
						),
						Fields.field(
								"requiredLinearHistory",
								actual.requiredLinearHistory(),
								base.requiredLinearHistory
						),
						Fields.field(
								"allowForcePushes",
								actual.allowForcePushes(),
								base.allowForcePushes
						),
						Fields.field(
								"allowDeletions",
								actual.allowDeletions(),
								base.allowDeletions
						),
						Fields.field(
								"blockCreations",
								actual.blockCreations(),
								base.blockCreations
						),
						Fields.field(
								"lockBranch",
								actual.lockBranch(),
								base.lockBranch
						),
						Fields.field(
								"allowForkSyncing",
								actual.allowForkSyncing(),
								base.allowForkSyncing
						),
						Fields.field(
								"requireConversationResolution",
								actual.requireConversationResolution(),
								base.requireConversationResolution
						),
						Fields.field(
								"strictStatusChecks",
								actual.strictStatusChecks(),
								base.strictStatusChecks
						)
				)
		);
		Fields.objects(
				"requiredStatusChecks",
				statusChecks(actual.requiredStatusChecks())
		).ifPresent(members::add);

		members.addAll(
				pullRequestReviewsMembers(
						actual.pullRequestReviews()
								.orElse(NO_PULL_REQUEST_REVIEWS),
						base
				)
		);
		members.addAll(
				restrictionsMembers(
						actual.restrictions().orElse(NO_RESTRICTIONS),
						base
				)
		);

		return new PklNode.Field(pattern, new PklNode.Obj(members));
	}

	private static List<PklNode.Member> pullRequestReviewsMembers(
			ActualBranchProtection.PullRequestReviews rpr,
			Drifty.BranchProtection base
	) {
		return Fields.members(
				Fields.field(
						"requiredApprovingReviewCount",
						rpr.requiredApprovingReviewCount(),
						toInteger(base.requiredApprovingReviewCount)
				),
				Fields.field(
						"dismissStaleReviews",
						rpr.dismissStaleReviews(),
						base.dismissStaleReviews
				),
				Fields.field(
						"requireCodeOwnerReviews",
						rpr.requireCodeOwnerReviews(),
						base.requireCodeOwnerReviews
				),
				Fields.field(
						"requireLastPushApproval",
						rpr.requireLastPushApproval(),
						base.requireLastPushApproval
				),
				Fields.strings(
						"dismissalUsers",
						rpr.dismissalRestrictions().users(),
						base.dismissalUsers
				),
				Fields.strings(
						"dismissalTeams",
						rpr.dismissalRestrictions().teams(),
						base.dismissalTeams
				),
				Fields.strings(
						"dismissalApps",
						rpr.dismissalRestrictions().apps(),
						base.dismissalApps
				),
				Fields.strings(
						"bypassPullRequestUsers",
						rpr.bypassPullRequestAllowances().users(),
						base.bypassPullRequestUsers
				),
				Fields.strings(
						"bypassPullRequestTeams",
						rpr.bypassPullRequestAllowances().teams(),
						base.bypassPullRequestTeams
				),
				Fields.strings(
						"bypassPullRequestApps",
						rpr.bypassPullRequestAllowances().apps(),
						base.bypassPullRequestApps
				)
		);
	}

	private static List<PklNode.Member> restrictionsMembers(
			ActualBranchProtection.Restrictions restrictions,
			Drifty.BranchProtection base
	) {
		return Fields.members(
				Fields.strings("users", restrictions.users(), base.users),
				Fields.strings("teams", restrictions.teams(), base.teams),
				Fields.strings("apps", restrictions.apps(), base.apps)
		);
	}

	/** The schema has no default for a nullable {@code Int}; null it stays. */
	private static Integer toInteger(Long value) {
		return value == null ? null : value.intValue();
	}

	private static List<PklNode> statusChecks(Set<StatusCheck> checks) {
		return checks.stream()
				.sorted(Comparator.comparing(StatusCheck::context))
				.<PklNode>map(BranchProtectionExporter::statusCheck)
				.toList();
	}

	/**
	 * {@code appId} keeps its schema default of null, so a check with none set
	 * omits the field rather than writing {@code appId = null} — the same shape
	 * {@code RulesetExporter} writes its own required status checks in.
	 */
	private static PklNode.Obj statusCheck(StatusCheck check) {
		var members = new ArrayList<PklNode.Member>();
		members.add(Fields.required("context", check.context()).orElseThrow());
		if (check.appId() != null) {
			members.add(
					Fields.required("appId", check.appId().longValue())
							.orElseThrow()
			);
		}
		return new PklNode.Obj(members);
	}

}
