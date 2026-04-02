package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RulesetRequest(
		String name,
		RulesetTarget target,
		RulesetEnforcement enforcement,
		@JsonInclude(
			JsonInclude.Include.NON_EMPTY
		) List<BypassActorRequest> bypassActors,
		Conditions conditions,
		List<Rule> rules
) {

	public RulesetRequest {
		bypassActors = bypassActors == null ? null : List.copyOf(bypassActors);
		rules = rules == null ? null : List.copyOf(rules);
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record BypassActorRequest(
			Long actorId,
			RulesetDetailsResponse.BypassActor.ActorType actorType,
			RulesetDetailsResponse.BypassActor.BypassMode bypassMode
	) {
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

}
