package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

@GitHubEndpoint(
		response = { "GET /repos/{owner}/{repo}/rulesets/{ruleset_id}",
				"GET /orgs/{org}/rulesets/{ruleset_id}" },
		unmanaged = {
				"rules[code_coverage] — a rule type GitHub added; Rule has no subtype for it, so it reaches the catch-all and nothing compares it",
				"rules[code_quality] — a rule type GitHub added, as above",
				"rules[copilot_code_review] — a rule type GitHub added, as above",
				"rules[license_compliance_scanning] — a rule type GitHub added, as above" }
)
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

	/**
	 * Nothing compares this — it describes the token, not the ruleset — but it
	 * is parsed, and a value with no constant here is an
	 * {@code InvalidFormatException} that ends the repository's check. GitHub
	 * added {@code exempt}; drifty read four values as three until
	 * {@code GitHubApiContractTest} said so.
	 */
	public enum CurrentUserCanBypass {
		@JsonProperty("always")
		ALWAYS, @JsonProperty("pull_requests_only")
		PULL_REQUESTS_ONLY, @JsonProperty("never")
		NEVER, @JsonProperty("exempt")
		EXEMPT
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
			TEAM, @JsonProperty("DeployKey")
			DEPLOY_KEY, @JsonProperty("User")
			USER
		}

		public enum BypassMode {
			@JsonProperty("always")
			ALWAYS, @JsonProperty("pull_request")
			PULL_REQUEST, @JsonProperty("exempt")
			EXEMPT
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
