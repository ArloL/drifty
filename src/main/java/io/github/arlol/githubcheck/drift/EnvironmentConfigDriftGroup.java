package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.client.EnvironmentUpdateRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

public class EnvironmentConfigDriftGroup extends DriftGroup {

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
		this.desired = Map.copyOf(desired);
		this.actual = Map.copyOf(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public String name() {
		return "environment_config";
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
								getFixAction(envName, wantEnv)
						)
				);
				continue;
			}

			var items = new ArrayList<DriftItem>();

			if (wantEnv.waitTimer > 0) {
				ocompare(
						envName + ".wait_timer",
						(int) wantEnv.waitTimer,
						actualEnv.waitTimer()
				).ifPresent(items::add);
			}

			if (wantEnv.protectedBranches || wantEnv.customBranchPolicies) {
				ocompare(
						envName + ".deployment_branch_policy.protected_branches",
						wantEnv.protectedBranches,
						actualEnv.protectedBranches()
				).ifPresent(items::add);
				ocompare(
						envName + ".deployment_branch_policy.custom_branch_policies",
						wantEnv.customBranchPolicies,
						actualEnv.customBranchPolicies()
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
