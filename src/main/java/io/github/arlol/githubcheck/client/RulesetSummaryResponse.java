package io.github.arlol.githubcheck.client;

@GitHubEndpoint(response = "GET /orgs/{org}/rulesets")
public record RulesetSummaryResponse(
		long id,
		String name,
		RulesetEnforcement enforcement,
		String nodeId,
		RulesetSourceType sourceType,
		String source,
		String createdAt,
		String updatedAt
) {
}
