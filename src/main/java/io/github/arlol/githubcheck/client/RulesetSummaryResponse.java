package io.github.arlol.githubcheck.client;

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
