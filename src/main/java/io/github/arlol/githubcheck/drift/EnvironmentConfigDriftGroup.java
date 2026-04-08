package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.EnvironmentUpdateRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.config.EnvironmentArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class EnvironmentConfigDriftGroup extends DriftGroup {

	private final Map<String, EnvironmentArgs> desired;
	private final Map<String, EnvironmentDetailsResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentConfigDriftGroup(
			RepositoryArgs desired,
			Map<String, EnvironmentDetailsResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Map.copyOf(desired.environments());
		this.actual = Map.copyOf(actual);
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "environment_config";
	}

	@Override
	public List<DriftFix> detect() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();
			EnvironmentDetailsResponse actualEnv = actual.get(envName);

			if (actualEnv == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(
										"environment." + envName
								),
								getFixAction(envName, wantEnv)
						)
				);
				continue;
			}

			var items = new ArrayList<DriftItem>();

			if (wantEnv.waitTimer() != null) {
				ocompare(
						"environment." + envName + ".wait_timer",
						wantEnv.waitTimer(),
						actualEnv.getWaitTimer()
				).ifPresent(items::add);
			}

			if (wantEnv.deploymentBranchPolicy() != null) {
				ocompare(
						"environment." + envName
								+ ".deployment_branch_policy.protected_branches",
						wantEnv.deploymentBranchPolicy().protectedBranches(),
						actualEnv.deploymentBranchPolicy() != null
								&& actualEnv.deploymentBranchPolicy()
										.protectedBranches()
				).ifPresent(items::add);
				ocompare(
						"environment." + envName
								+ ".deployment_branch_policy.custom_branch_policies",
						wantEnv.deploymentBranchPolicy().customBranchPolicies(),
						actualEnv.deploymentBranchPolicy() != null
								&& actualEnv.deploymentBranchPolicy()
										.customBranchPolicies()
				).ifPresent(items::add);
			}

			if (!wantEnv.reviewers().isEmpty()) {
				ocompare(
						"environment." + envName + ".reviewers",
						wantEnv.reviewers()
								.stream()
								.map(r -> r.type().name() + ":" + r.id())
								.toList(),
						actualEnv.getReviewerIds()
				).ifPresent(items::add);
			}

			fixes.add(new DriftFix(items, getFixAction(envName, wantEnv)));
		}

		return fixes;
	}

	private DriftFix.FixAction getFixAction(
			String envName,
			EnvironmentArgs wantEnv
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

	private static EnvironmentUpdateRequest buildEnvironmentUpdateRequest(
			EnvironmentArgs args
	) {
		EnvironmentUpdateRequest.DeploymentBranchPolicy dbp = null;
		if (args.deploymentBranchPolicy() != null) {
			var policy = args.deploymentBranchPolicy();
			dbp = new EnvironmentUpdateRequest.DeploymentBranchPolicy(
					policy.protectedBranches(),
					policy.customBranchPolicies()
			);
		}

		return new EnvironmentUpdateRequest(
				args.waitTimer(),
				args.reviewers().isEmpty() ? null : args.reviewers(),
				dbp
		);
	}

}
