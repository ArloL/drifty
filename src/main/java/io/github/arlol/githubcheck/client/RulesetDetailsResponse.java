package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RulesetDetailsResponse(
		long id,
		String name,
		RulesetTarget target,
		RulesetEnforcement enforcement,
		String nodeId,
		RulesetSourceType sourceType,
		String source,
		CurrentUserCanBypass currentUserCanBypass,
		String createdAt,
		String updatedAt,
		List<BypassActor> bypassActors,
		Conditions conditions,
		List<Rule> rules
) {

	public RulesetDetailsResponse {
		bypassActors = bypassActors == null ? null : List.copyOf(bypassActors);
		rules = rules == null ? null : List.copyOf(rules);
	}

	public enum CurrentUserCanBypass {
		@JsonProperty("always")
		ALWAYS, @JsonProperty("pull_requests_only")
		PULL_REQUESTS_ONLY, @JsonProperty("never")
		NEVER
	}

	public record BypassActor(
			Long actorId,
			ActorType actorType,
			BypassMode bypassMode
	) {

		public enum ActorType {
			@JsonProperty("Integration")
			INTEGRATION, @JsonProperty("OrganizationAdmin")
			ORGANIZATION_ADMIN, @JsonProperty("RepositoryRole")
			REPOSITORY_ROLE, @JsonProperty("Team")
			TEAM
		}

		public enum BypassMode {
			@JsonProperty("always")
			ALWAYS, @JsonProperty("pull_request")
			PULL_REQUEST
		}

	}

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

}
