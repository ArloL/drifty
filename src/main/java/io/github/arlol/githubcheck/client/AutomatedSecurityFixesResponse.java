package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		response = "GET /repos/{owner}/{repo}/automated-security-fixes",
		unmanaged = {
				"paused — whether GitHub has stopped acting on the setting, not what the setting is; drifty compares and writes the setting" }
)
public record AutomatedSecurityFixesResponse(
		boolean enabled
) {
}
