package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvironmentUpdateRequest(
		Integer waitTimer,
		Boolean preventSelfReview,
		List<Reviewer> reviewers,
		// Always written: GitHub reads an omitted policy as "leave it", and a
		// null one as "let every branch deploy", which is what clearing it
		// takes.
		@JsonInclude(
			JsonInclude.Include.ALWAYS
		) DeploymentBranchPolicy deploymentBranchPolicy
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
