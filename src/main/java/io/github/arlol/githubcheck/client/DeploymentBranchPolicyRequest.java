package io.github.arlol.githubcheck.client;

public record DeploymentBranchPolicyRequest(
		String name,
		BranchPolicyType type
) {
}
