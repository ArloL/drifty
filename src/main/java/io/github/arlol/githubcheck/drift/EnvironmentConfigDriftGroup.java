package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.client.EnvironmentDetailsResponse;
import io.github.arlol.githubcheck.client.EnvironmentUpdateRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;

public class EnvironmentConfigDriftGroup extends DriftGroup {

	private final Map<String, Drifty.Environment> desired;
	private final Map<String, EnvironmentDetailsResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentConfigDriftGroup(
			Drifty.Repository desired,
			Map<String, EnvironmentDetailsResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Map.copyOf(desired.environments);
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
			Drifty.Environment wantEnv = entry.getValue();
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

			if (wantEnv.waitTimer > 0) {
				ocompare(
						"environment." + envName + ".wait_timer",
						(int) wantEnv.waitTimer,
						actualEnv.getWaitTimer()
				).ifPresent(items::add);
			}

			if (wantEnv.protectedBranches || wantEnv.customBranchPolicies) {
				ocompare(
						"environment." + envName
								+ ".deployment_branch_policy.protected_branches",
						wantEnv.protectedBranches,
						actualEnv.deploymentBranchPolicy() != null
								&& actualEnv.deploymentBranchPolicy()
										.protectedBranches()
				).ifPresent(items::add);
				ocompare(
						"environment." + envName
								+ ".deployment_branch_policy.custom_branch_policies",
						wantEnv.customBranchPolicies,
						actualEnv.deploymentBranchPolicy() != null
								&& actualEnv.deploymentBranchPolicy()
										.customBranchPolicies()
				).ifPresent(items::add);
			}

			fixes.add(new DriftFix(items, getFixAction(envName, wantEnv)));
		}

		return fixes;
	}

	private DriftFix.FixAction getFixAction(
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

	private static EnvironmentUpdateRequest buildEnvironmentUpdateRequest(
			Drifty.Environment args
	) {
		EnvironmentUpdateRequest.DeploymentBranchPolicy dbp = null;
		if (args.protectedBranches || args.customBranchPolicies) {
			dbp = new EnvironmentUpdateRequest.DeploymentBranchPolicy(
					args.protectedBranches,
					args.customBranchPolicies
			);
		}

		return new EnvironmentUpdateRequest(
				args.waitTimer > 0 ? Integer.valueOf((int) args.waitTimer)
						: null,
				null,
				dbp
		);
	}

}
