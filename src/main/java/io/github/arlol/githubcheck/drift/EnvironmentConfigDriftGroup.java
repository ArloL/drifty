package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();
			EnvironmentDetailsResponse actualEnv = actual.get(envName);

			if (actualEnv == null) {
				items.add(
						new DriftItem.SectionMissing("environment." + envName)
				);
				continue;
			}

			if (wantEnv.waitTimer() != null) {
				items.addAll(
						compare(
								"environment." + envName + ".wait_timer",
								wantEnv.waitTimer(),
								actualEnv.getWaitTimer()
						)
				);
			}
			if (wantEnv.deploymentBranchPolicy() != null) {
				var want = wantEnv.deploymentBranchPolicy();
				var got = actualEnv.deploymentBranchPolicy();
				boolean gotProtected = got != null && got.protectedBranches();
				boolean gotCustom = got != null && got.customBranchPolicies();
				items.addAll(
						compare(
								"environment." + envName
										+ ".deployment_branch_policy.protected_branches",
								want.protectedBranches(),
								gotProtected
						)
				);
				items.addAll(
						compare(
								"environment." + envName
										+ ".deployment_branch_policy.custom_branch_policies",
								want.customBranchPolicies(),
								gotCustom
						)
				);
			}
			if (!wantEnv.reviewers().isEmpty()) {
				Set<String> want = wantEnv.reviewers()
						.stream()
						.map(r -> r.type().name() + ":" + r.id())
						.collect(Collectors.toCollection(HashSet::new));
				Set<String> got = actualEnv.getReviewerIds();
				items.addAll(
						compareSets(
								"environment." + envName + ".reviewers",
								want,
								got
						)
				);
			}
		}

		return items;
	}

	@Override
	public FixResult fix() {
		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();

			var payload = buildEnvironmentUpdateRequest(wantEnv);
			client.updateEnvironment(owner, repo, envName, payload);
		}
		return FixResult.success();
	}

	private static EnvironmentUpdateRequest buildEnvironmentUpdateRequest(
			EnvironmentArgs args
	) {
		List<EnvironmentUpdateRequest.Reviewer> reviewers = args.reviewers()
				.isEmpty()
						? null
						: args.reviewers()
								.stream()
								.map(
										r -> new EnvironmentUpdateRequest.Reviewer(
												r.type(),
												r.id()
										)
								)
								.toList();

		EnvironmentUpdateRequest.DeploymentBranchPolicy dbp = null;
		if (args.deploymentBranchPolicy() != null) {
			var policy = args.deploymentBranchPolicy();
			dbp = new EnvironmentUpdateRequest.DeploymentBranchPolicy(
					policy.protectedBranches(),
					policy.customBranchPolicies()
			);
		}

		return new EnvironmentUpdateRequest(args.waitTimer(), reviewers, dbp);
	}

}
