package io.github.arlol.githubcheck.client;

public record DeploymentBranchPolicyResponse(
		long id,
		String name,
		BranchPolicyType type
) {
}
