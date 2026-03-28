package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RulesetRequest(
		String name,
		RulesetTarget target,
		RulesetEnforcement enforcement,
		Conditions conditions,
		List<Rule> rules
) {

	public RulesetRequest {
		rules = rules == null ? null : List.copyOf(rules);
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Conditions(
			RefName refName,
			RepositoryName repositoryName,
			RepositoryId repositoryId,
			RepositoryProperty repositoryProperty
	) {

		public record RefName(
				List<String> include,
				List<String> exclude
		) {

			public RefName {
				include = include == null ? null : List.copyOf(include);
				exclude = exclude == null ? null : List.copyOf(exclude);
			}

		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record RepositoryName(
				List<String> include,
				List<String> exclude,
				@JsonProperty("protected") Boolean isProtected
		) {

			public RepositoryName {
				include = include == null ? null : List.copyOf(include);
				exclude = exclude == null ? null : List.copyOf(exclude);
			}

		}

		public record RepositoryId(
				List<Long> repositoryIds
		) {

			public RepositoryId {
				repositoryIds = repositoryIds == null ? null
						: List.copyOf(repositoryIds);
			}

		}

		public record RepositoryProperty(
				List<PropertyCondition> include,
				List<PropertyCondition> exclude
		) {

			public RepositoryProperty {
				include = include == null ? null : List.copyOf(include);
				exclude = exclude == null ? null : List.copyOf(exclude);
			}

			@JsonInclude(JsonInclude.Include.NON_NULL)
			public record PropertyCondition(
					String name,
					List<String> propertyValues,
					String source
			) {

				public PropertyCondition {
					propertyValues = propertyValues == null ? null
							: List.copyOf(propertyValues);
				}

			}

		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Rule(
			RulesetRuleType type,
			Parameters parameters
	) {

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Parameters(
				// required_status_checks rule fields
				List<StatusCheck> requiredStatusChecks,
				Boolean strictRequiredStatusChecksPolicy,
				// pull_request rule fields
				Integer requiredApprovingReviewCount,
				Boolean dismissStaleReviewsOnPush,
				Boolean requireCodeOwnerReview,
				Boolean requireLastPushApproval
		) {

			public Parameters {
				requiredStatusChecks = requiredStatusChecks == null ? null
						: List.copyOf(requiredStatusChecks);
			}

			@JsonInclude(JsonInclude.Include.NON_NULL)
			public record StatusCheck(
					String context,
					Integer integrationId
			) {
			}

		}

	}

}
