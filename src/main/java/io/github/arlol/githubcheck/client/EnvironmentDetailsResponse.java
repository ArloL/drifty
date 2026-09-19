package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/environments/{environment_name}",
		unmanaged = {
				"protection_rules.reviewers.reviewer.* — EnvironmentConfigDriftGroup compares a reviewer by id and type; the rest is GitHub's description of the user or team, which the environment does not own",
				"protection_rules.id — a protection rule is compared by its type and what it holds; the config cannot name an id GitHub assigns" }
)
public record EnvironmentDetailsResponse(
		String name,
		List<ProtectionRule> protectionRules,
		DeploymentBranchPolicy deploymentBranchPolicy
) {

	public EnvironmentDetailsResponse {
		protectionRules = protectionRules != null ? List.copyOf(protectionRules)
				: List.of();
	}

	public enum ProtectionRuleType {
		@JsonProperty("wait_timer")
		WAIT_TIMER, @JsonProperty("required_reviewers")
		REQUIRED_REVIEWERS, @JsonProperty("branch_policy")
		BRANCH_POLICY
	}

	public record ProtectionRule(
			ProtectionRuleType type,
			Integer waitTimer,
			Boolean preventSelfReview,
			List<Reviewer> reviewers
	) {

		public ProtectionRule {
			reviewers = reviewers != null ? List.copyOf(reviewers) : List.of();
		}

	}

	public record Reviewer(
			EnvironmentReviewerType type,
			ReviewerEntity reviewer
	) {
	}

	public record ReviewerEntity(
			Long id,
			String login,
			String slug
	) {
	}

	public record DeploymentBranchPolicy(
			boolean protectedBranches,
			boolean customBranchPolicies
	) {
	}

}
