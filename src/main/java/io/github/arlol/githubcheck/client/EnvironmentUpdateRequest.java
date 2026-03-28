package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvironmentUpdateRequest(
		Integer waitTimer,
		List<Reviewer> reviewers,
		DeploymentBranchPolicy deploymentBranchPolicy
) {

	public EnvironmentUpdateRequest {
		reviewers = reviewers != null ? List.copyOf(reviewers) : null;
	}

	public record Reviewer(
			EnvironmentReviewerType type,
			long id
	) {
	}

	public record DeploymentBranchPolicy(
			boolean protectedBranches,
			boolean customBranchPolicies
	) {
	}

}
