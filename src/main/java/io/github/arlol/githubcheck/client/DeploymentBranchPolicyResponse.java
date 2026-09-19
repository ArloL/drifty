package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "POST /repos/{owner}/{repo}/environments/{environment_name}/deployment-branch-policies"
)
public record DeploymentBranchPolicyResponse(
		long id,
		String name,
		BranchPolicyType type
) {
}
