package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

@GitHubEndpoint(
		request = { "POST /repos/{owner}/{repo}/rulesets",
				"PUT /repos/{owner}/{repo}/rulesets/{ruleset_id}",
				"POST /orgs/{org}/rulesets",
				"PUT /orgs/{org}/rulesets/{ruleset_id}" },
		unmanaged = {
				"rules[pull_request].parameters.dismissal_restriction — no drift group compares it",
				"rules[pull_request].parameters.required_reviewers — no drift group compares it",
				"rules[required_status_checks].parameters.do_not_enforce_on_create — no drift group compares it",
				"rules[code_coverage] — a rule type GitHub added; Rule has no subtype for it, so it reaches the catch-all and nothing compares it",
				"rules[code_quality] — a rule type GitHub added, as above",
				"rules[copilot_code_review] — a rule type GitHub added, as above",
				"rules[license_compliance_scanning] — a rule type GitHub added, as above" },
		undocumented = {
				"rules[merge_queue] — Rule carries it for the repository endpoints, where the spec has the branch; the organization ruleset schema does not",
				"conditions.repository_id — the organization endpoints accept it and OrgRulesetDriftGroup sends it; a repository ruleset has no condition beyond ref_name",
				"conditions.repository_name — as above",
				"conditions.repository_property — as above",
				"target#repository — an organization ruleset may target repository; config/drifty.pkl refuses that target in Repository.rulesets, so the value never reaches the repository endpoints" }
)
public record RulesetRequest(
		String name,
		RulesetTarget target,
		RulesetEnforcement enforcement,
		@JsonInclude(
			JsonInclude.Include.NON_EMPTY
		) List<BypassActorRequest> bypassActors,
		/**
		 * Omitted when null rather than sent as {@code null} or {@code {}}: a
		 * repository push ruleset has no conditions at all.
		 */
		@JsonInclude(
			JsonInclude.Include.NON_NULL
		) @Nullable Conditions conditions,
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
			@Nullable RefName refName,
			@Nullable RepositoryName repositoryName,
			@Nullable RepositoryId repositoryId,
			@Nullable RepositoryProperty repositoryProperty
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
				@JsonProperty("protected") @Nullable Boolean isProtected
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
