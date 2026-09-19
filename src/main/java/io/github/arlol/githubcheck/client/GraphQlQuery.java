package io.github.arlol.githubcheck.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.DeserializationFeature;

import io.github.arlol.githubcheck.client.GraphQlRepositoryResponse.Section;

/**
 * The query {@link GitHubClient#graphqlRepository} sends and how its answer is
 * read.
 * <p>
 * Four aliased {@code repository} selections rather than one with four fields:
 * GitHub answers a forbidden field by nulling the whole {@code repository}
 * object, so one alias each is what lets a token that may not read branch
 * protection still read the rulesets. Each alias is failed on its own by the
 * error whose path names it.
 */
final class GraphQlQuery {

	/**
	 * Reads the rewritten JSON back with the same settings {@link GitHubClient}
	 * uses for a REST body, because that is the shape {@link GraphQlShape}
	 * produces.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					true
			);

	private static final String RULESETS = """
			rulesets(first: 100, includeParents: false) { nodes {
			  databaseId name target enforcement source { __typename }
			  conditions { refName { include exclude } }
			  bypassActors(first: 100) { nodes {
			    bypassMode repositoryRoleDatabaseId organizationAdmin deployKey
			    actor { __typename ... on App { databaseId } ... on Team { databaseId }
			        ... on User { databaseId } }
			  } }
			  rules(first: 100) { nodes { type parameters { __typename
			    ... on PullRequestParameters { requiredApprovingReviewCount
			        dismissStaleReviewsOnPush requireCodeOwnerReview
			        requireLastPushApproval requiredReviewThreadResolution
			        allowedMergeMethods }
			    ... on RequiredStatusChecksParameters { strictRequiredStatusChecksPolicy
			        requiredStatusChecks { context integrationId } }
			    ... on UpdateParameters { updateAllowsFetchAndMerge }
			    ... on CommitMessagePatternParameters { name negate operator pattern }
			    ... on CommitAuthorEmailPatternParameters { name negate operator pattern }
			    ... on CommitterEmailPatternParameters { name negate operator pattern }
			    ... on BranchNamePatternParameters { name negate operator pattern }
			    ... on TagNamePatternParameters { name negate operator pattern }
			    ... on MergeQueueParameters { checkResponseTimeoutMinutes
			        groupingStrategy maxEntriesToBuild maxEntriesToMerge mergeMethod
			        minEntriesToMerge minEntriesToMergeWaitMinutes }
			    ... on RequiredDeploymentsParameters { requiredDeploymentEnvironments }
			    ... on WorkflowsParameters { doNotEnforceOnCreate
			        workflows { path ref repositoryId sha } }
			    ... on CodeScanningParameters { codeScanningTools { tool
			        alertsThreshold securityAlertsThreshold } }
			    ... on FilePathRestrictionParameters { restrictedFilePaths }
			    ... on MaxFilePathLengthParameters { maxFilePathLength }
			    ... on FileExtensionRestrictionParameters { restrictedFileExtensions }
			    ... on MaxFileSizeParameters { maxFileSize }
			  } } }
			} }""";

	private static final String BRANCH_PROTECTIONS = """
			branchProtectionRules(first: 100) { nodes {
			  pattern matchingRefs(first: 100) { nodes { name } }
			  isAdminEnforced requiresLinearHistory allowsForcePushes allowsDeletions
			  blocksCreations requiresConversationResolution requiresCommitSignatures
			  lockBranch lockAllowsFetchAndMerge
			  requiresStatusChecks requiresStrictStatusChecks
			  requiredStatusChecks { context app { databaseId } }
			  requiresApprovingReviews requiredApprovingReviewCount
			  dismissesStaleReviews requiresCodeOwnerReviews requireLastPushApproval
			  restrictsPushes pushAllowances(first: 100) { nodes { actor { __typename
			      ... on User { login } ... on Team { slug } ... on App { slug } } } }
			  restrictsReviewDismissals
			  reviewDismissalAllowances(first: 100) { nodes { actor { __typename
			      ... on User { login } ... on Team { slug } ... on App { slug } } } }
			  bypassPullRequestAllowances(first: 100) { nodes { actor { __typename
			      ... on User { login } ... on Team { slug } ... on App { slug } } } }
			} }""";

	private static final String COLLABORATORS = """
			collaborators(affiliation: DIRECT, first: 100) {
			  edges { permission node { login } }
			}""";

	private static final String VULNERABILITY_ALERTS = "hasVulnerabilityAlertsEnabled";

	private GraphQlQuery() {
	}

	static String repository(String owner, String repo) {
		return "query { %s %s %s %s }".formatted(
				alias("rs", owner, repo, RULESETS),
				alias("bp", owner, repo, BRANCH_PROTECTIONS),
				alias("co", owner, repo, COLLABORATORS),
				alias("va", owner, repo, VULNERABILITY_ALERTS)
		);
	}

	private static String alias(
			String name,
			String owner,
			String repo,
			String selection
	) {
		return "%s: repository(owner: \"%s\", name: \"%s\") { %s }"
				.formatted(name, owner, repo, selection);
	}

	static GraphQlRepositoryResponse read(JsonNode body) {
		Map<String, String> errors = errorsByAlias(body);
		JsonNode data = body.path("data");
		return new GraphQlRepositoryResponse(
				section(data, errors, "rs", GraphQlQuery::rulesets),
				section(data, errors, "bp", GraphQlQuery::branchProtections),
				section(data, errors, "co", GraphQlQuery::collaborators),
				section(
						data,
						errors,
						"va",
						node -> node.path("hasVulnerabilityAlertsEnabled")
								.asBoolean()
				)
		);
	}

	/**
	 * The first error naming each alias. An error's path starts with the alias
	 * it belongs to, which is the whole reason the four are aliased.
	 */
	private static Map<String, String> errorsByAlias(JsonNode body) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (JsonNode error : body.path("errors")) {
			String alias = error.path("path").path(0).asText("");
			errors.putIfAbsent(
					alias,
					error.path("message").asText("GraphQL error")
			);
		}
		return errors;
	}

	private static <T> Section<T> section(
			JsonNode data,
			Map<String, String> errors,
			String alias,
			java.util.function.Function<JsonNode, T> read
	) {
		String error = errors.get(alias);
		if (error != null) {
			return Section.failed(error);
		}
		JsonNode node = data.path(alias);
		if (node.isMissingNode() || node.isNull()) {
			return Section.failed("GraphQL returned no " + alias + " section");
		}
		return Section.of(read.apply(node));
	}

	private static List<RulesetDetailsResponse> rulesets(JsonNode node) {
		List<RulesetDetailsResponse> out = new ArrayList<>();
		for (JsonNode ruleset : node.path("rulesets").path("nodes")) {
			out.add(
					convert(
							GraphQlShape.ruleset(ruleset),
							RulesetDetailsResponse.class
					)
			);
		}
		return List.copyOf(out);
	}

	/**
	 * Keyed by branch, not by the pattern GraphQL keys a rule on:
	 * {@code matchingRefs} is what {@code GET .../branches?protected=true}
	 * answered, and drifty's model has always been per branch.
	 */
	private static Map<String, BranchProtectionResponse> branchProtections(
			JsonNode node
	) {
		Map<String, BranchProtectionResponse> out = new LinkedHashMap<>();
		for (JsonNode rule : node.path("branchProtectionRules").path("nodes")) {
			BranchProtectionResponse protection = convert(
					GraphQlShape.branchProtection(rule),
					BranchProtectionResponse.class
			);
			for (JsonNode ref : rule.path("matchingRefs").path("nodes")) {
				out.put(ref.path("name").asText(), protection);
			}
		}
		return Map.copyOf(out);
	}

	private static List<CollaboratorResponse> collaborators(JsonNode node) {
		List<CollaboratorResponse> out = new ArrayList<>();
		for (JsonNode edge : node.path("collaborators").path("edges")) {
			out.add(
					convert(
							GraphQlShape.collaborator(edge),
							CollaboratorResponse.class
					)
			);
		}
		return List.copyOf(out);
	}

	private static <T> T convert(JsonNode node, Class<T> type) {
		try {
			return MAPPER.treeToValue(node, type);
		} catch (JsonProcessingException e) {
			throw new GitHubApiException(
					"Failed to read " + type.getSimpleName() + " from GraphQL",
					e
			);
		}
	}

}
