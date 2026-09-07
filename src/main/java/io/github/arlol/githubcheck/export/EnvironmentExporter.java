package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.client.EnvironmentReviewerType;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A deployment environment, as the config lines that differ from the schema's
 * defaults.
 * <p>
 * {@link ActualEnvironment#reviewers()} arrives as one set of {@code Type:name}
 * keys built by {@link ActualTypes#reviewerKey} — the same prefixes
 * {@code EnvironmentConfigDriftGroup} builds when it recomputes
 * {@code desiredReviewers} to compare against this set — so splitting by that
 * prefix here is comparing like with like rather than inventing a second
 * convention. {@link ActualEnvironment#branchPolicies()} is split the same way,
 * by the {@code branch}/{@code tag} type string {@code ActualTypes} assigns
 * from {@code BranchPolicyType} when it builds each policy.
 */
public final class EnvironmentExporter {

	private static final String SECRET_VALUES_NOTE = "secret values are never returned by GitHub; supply them through DRIFTY_GITHUB_SECRETS";

	private static final String USER_PREFIX = ActualTypes
			.reviewerKey(EnvironmentReviewerType.USER, "");

	private static final String TEAM_PREFIX = ActualTypes
			.reviewerKey(EnvironmentReviewerType.TEAM, "");

	private static final String BRANCH_POLICY_TYPE = "branch";

	private static final String TAG_POLICY_TYPE = "tag";

	private EnvironmentExporter() {
	}

	public static PklNode.Member entry(
			String name,
			ActualEnvironment actual,
			List<ActualSecret> secrets,
			List<ActualVariable> variables,
			Drifty.Environment base
	) {
		var members = new ArrayList<PklNode.Member>();

		Fields.strings(
				"secrets",
				secrets.stream().map(ActualSecret::name).toList(),
				base.secrets
		).ifPresent(members::add);
		if (!secrets.isEmpty()) {
			members.add(Fields.note(SECRET_VALUES_NOTE));
		}

		Fields.mapping("variables", variableEntries(variables))
				.ifPresent(members::add);

		members.addAll(
				Fields.members(
						Fields.field(
								"waitTimer",
								(long) actual.waitTimer(),
								base.waitTimer
						),
						Fields.field(
								"preventSelfReview",
								actual.preventSelfReview(),
								base.preventSelfReview
						)
				)
		);

		Fields.strings(
				"reviewerUsers",
				byPrefix(actual.reviewers(), USER_PREFIX),
				base.reviewerUsers
		).ifPresent(members::add);
		Fields.strings(
				"reviewerTeams",
				byPrefix(actual.reviewers(), TEAM_PREFIX),
				base.reviewerTeams
		).ifPresent(members::add);

		members.addAll(
				Fields.members(
						Fields.field(
								"protectedBranches",
								actual.protectedBranches(),
								base.protectedBranches
						),
						Fields.field(
								"customBranchPolicies",
								actual.customBranchPolicies(),
								base.customBranchPolicies
						)
				)
		);

		Fields.strings(
				"deploymentBranchPatterns",
				byType(actual.branchPolicies(), BRANCH_POLICY_TYPE),
				base.deploymentBranchPatterns
		).ifPresent(members::add);
		Fields.strings(
				"deploymentTagPatterns",
				byType(actual.branchPolicies(), TAG_POLICY_TYPE),
				base.deploymentTagPatterns
		).ifPresent(members::add);

		return new PklNode.Field(name, new PklNode.Obj(members));
	}

	private static List<String> byPrefix(Set<String> reviewers, String prefix) {
		return reviewers.stream()
				.filter(reviewer -> reviewer.startsWith(prefix))
				.map(reviewer -> reviewer.substring(prefix.length()))
				.toList();
	}

	private static List<String> byType(
			List<ActualEnvironment.BranchPolicy> policies,
			String type
	) {
		return policies.stream()
				.filter(policy -> type.equals(policy.type()))
				.map(ActualEnvironment.BranchPolicy::name)
				.toList();
	}

	private static List<PklNode.Member> variableEntries(
			List<ActualVariable> variables
	) {
		return variables.stream()
				.sorted(Comparator.comparing(ActualVariable::name)).<PklNode
						.Member>map(
								variable -> new PklNode.Field(
										variable.name(),
										PklNode.Scalar.of(variable.value())
								)
						)
				.toList();
	}

}
