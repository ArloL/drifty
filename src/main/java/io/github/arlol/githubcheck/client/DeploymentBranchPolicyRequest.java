package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		request = "POST /repos/{owner}/{repo}/environments/{environment_name}/deployment-branch-policies"
)
public record DeploymentBranchPolicyRequest(
		String name,
		BranchPolicyType type
) {
}
