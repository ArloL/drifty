package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "GET /orgs/{org}/rulesets",
		unmanaged = { "_links — navigation, not a setting",
				"bypass_actors — the listing is read for id and name; RulesetDriftGroup fetches each ruleset in full and compares it there",
				"conditions — fetched in full, as above",
				"current_user_can_bypass — describes the token, not the ruleset",
				"rules — fetched in full, as above",
				"target — fetched in full, as above" }
)
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
