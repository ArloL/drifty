package io.github.arlol.githubcheck.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

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

	public Integer getWaitTimer() {
		return protectionRules.stream()
				.filter(r -> ProtectionRuleType.WAIT_TIMER.equals(r.type()))
				.findFirst()
				.map(ProtectionRule::waitTimer)
				.orElse(null);
	}

	public Set<String> getReviewerIds() {
		Set<String> ids = new HashSet<>();
		for (ProtectionRule rule : protectionRules) {
			if (ProtectionRuleType.REQUIRED_REVIEWERS.equals(rule.type())
					&& rule.reviewers() != null) {
				for (Reviewer r : rule.reviewers()) {
					if (r.reviewer() != null && r.reviewer().id() != null) {
						ids.add(r.type().name() + ":" + r.reviewer().id());
					}
				}
			}
		}
		return ids;
	}

}
