package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.client.BranchPolicyType;
import io.github.arlol.githubcheck.client.DeploymentBranchPolicyRequest;
import io.github.arlol.githubcheck.client.EnvironmentReviewerType;
import io.github.arlol.githubcheck.client.EnvironmentUpdateRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Deployment environments: their protection rules, reviewers and branch
 * policies.
 * <p>
 * An environment's own settings share one {@link DriftFix} — a single PUT
 * writes them all — while each custom branch policy is its own, because
 * policies are created and deleted one request at a time. The PUT runs first,
 * since a policy cannot be created until {@code custom_branch_policies} is on.
 * <p>
 * Reviewers are compared by login and slug, which is what the config names, and
 * resolved to the ids the PUT wants only when a fix runs. Environments GitHub
 * has that the config does not declare are reported and left alone: deleting
 * one discards its secrets and deployment history.
 */
public class EnvironmentConfigDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, Drifty.Environment> desired;
	private final Map<String, ActualEnvironment> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentConfigDriftGroup(
			Map<String, Drifty.Environment> desired,
			Map<String, ActualEnvironment> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = new LinkedHashMap<>(desired);
		this.actual = new LinkedHashMap<>(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.ENVIRONMENT_CONFIG;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			Drifty.Environment wantEnv = entry.getValue();
			ActualEnvironment actualEnv = actual.get(envName);

			if (actualEnv == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(envName),
								updateAction(envName, wantEnv)
						)
				);
				if (wantEnv.customBranchPolicies) {
					for (String policy : desiredPolicies(wantEnv)) {
						fixes.add(createPolicyFix(envName, policy));
					}
				}
				continue;
			}

			fixes.add(
					new DriftFix(
							compareEnvironment(envName, wantEnv, actualEnv),
							updateAction(envName, wantEnv)
					)
			);
			if (wantEnv.customBranchPolicies
					|| actualEnv.customBranchPolicies()) {
				fixes.addAll(comparePolicies(envName, wantEnv, actualEnv));
			}
		}

		for (String envName : actual.keySet()) {
			if (!desired.containsKey(envName)) {
				var item = new DriftItem.SectionExtra(envName);
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not delete environments"
								)
						)
				);
			}
		}

		return fixes;
	}

	private static List<DriftItem> compareEnvironment(
			String envName,
			Drifty.Environment wantEnv,
			ActualEnvironment actualEnv
	) {
		return combine(
				compare(
						envName + ".wait_timer",
						(int) wantEnv.waitTimer,
						actualEnv.waitTimer()
				),
				compare(
						envName + ".prevent_self_review",
						wantEnv.preventSelfReview,
						actualEnv.preventSelfReview()
				),
				compare(
						envName + ".reviewers",
						desiredReviewers(wantEnv),
						actualEnv.reviewers()
				),
				compare(
						envName + ".deployment_branch_policy.protected_branches",
						wantEnv.protectedBranches,
						actualEnv.protectedBranches()
				),
				compare(
						envName + ".deployment_branch_policy.custom_branch_policies",
						wantEnv.customBranchPolicies,
						actualEnv.customBranchPolicies()
				)
		);
	}

	private List<DriftFix> comparePolicies(
			String envName,
			Drifty.Environment wantEnv,
			ActualEnvironment actualEnv
	) {
		var fixes = new ArrayList<DriftFix>();
		Set<String> wanted = desiredPolicies(wantEnv);
		Set<String> got = new HashSet<>();
		for (var policy : actualEnv.branchPolicies()) {
			got.add(policy.toString());
			if (!wanted.contains(policy.toString())) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionExtra(
										envName + ".branch_policies." + policy
								),
								() -> {
									client.deleteDeploymentBranchPolicy(
											owner,
											repo,
											envName,
											policy.id()
									);
									return FixResult.success();
								}
						)
				);
			}
		}
		for (String policy : wanted) {
			if (!got.contains(policy)) {
				fixes.add(createPolicyFix(envName, policy));
			}
		}
		return fixes;
	}

	private DriftFix createPolicyFix(String envName, String policy) {
		return new DriftFix(
				new DriftItem.SectionMissing(
						envName + ".branch_policies." + policy
				),
				() -> {
					client.createDeploymentBranchPolicy(
							owner,
							repo,
							envName,
							policyRequest(policy)
					);
					return FixResult.success();
				}
		);
	}

	/**
	 * The desired policies as {@code branch:<pattern>} and
	 * {@code tag:<pattern>}.
	 */
	private static Set<String> desiredPolicies(Drifty.Environment env) {
		var policies = new HashSet<String>();
		env.deploymentBranchPatterns.forEach(p -> policies.add("branch:" + p));
		env.deploymentTagPatterns.forEach(p -> policies.add("tag:" + p));
		return policies;
	}

	private static DeploymentBranchPolicyRequest policyRequest(String policy) {
		int colon = policy.indexOf(':');
		return new DeploymentBranchPolicyRequest(
				policy.substring(colon + 1),
				policy.startsWith("tag:") ? BranchPolicyType.TAG
						: BranchPolicyType.BRANCH
		);
	}

	private static Set<String> desiredReviewers(Drifty.Environment env) {
		var reviewers = new HashSet<String>();
		env.reviewerUsers.forEach(
				u -> reviewers.add(
						ActualTypes.reviewerKey(EnvironmentReviewerType.USER, u)
				)
		);
		env.reviewerTeams.forEach(
				t -> reviewers.add(
						ActualTypes.reviewerKey(EnvironmentReviewerType.TEAM, t)
				)
		);
		return reviewers;
	}

	private DriftFix.FixAction updateAction(
			String envName,
			Drifty.Environment wantEnv
	) {
		return () -> {
			client.updateEnvironment(
					owner,
					repo,
					envName,
					buildEnvironmentUpdateRequest(wantEnv)
			);
			return FixResult.success();
		};
	}

	/**
	 * Every field is sent, including the ones at their defaults: the PUT is
	 * what removes a wait timer, a reviewer or a branch policy someone added,
	 * and GitHub reads an omitted field as "leave it".
	 */
	private EnvironmentUpdateRequest buildEnvironmentUpdateRequest(
			Drifty.Environment args
	) {
		var reviewers = new ArrayList<EnvironmentUpdateRequest.Reviewer>();
		for (String login : args.reviewerUsers) {
			reviewers.add(
					new EnvironmentUpdateRequest.Reviewer(
							EnvironmentReviewerType.USER,
							client.getUserId(login)
					)
			);
		}
		for (String slug : args.reviewerTeams) {
			reviewers.add(
					new EnvironmentUpdateRequest.Reviewer(
							EnvironmentReviewerType.TEAM,
							client.getTeamId(owner, slug)
					)
			);
		}
		EnvironmentUpdateRequest.DeploymentBranchPolicy dbp = null;
		if (args.protectedBranches || args.customBranchPolicies) {
			dbp = new EnvironmentUpdateRequest.DeploymentBranchPolicy(
					args.protectedBranches,
					args.customBranchPolicies
			);
		}
		return new EnvironmentUpdateRequest(
				(int) args.waitTimer,
				args.preventSelfReview,
				reviewers,
				dbp
		);
	}

}
