package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		request = "PATCH /repos/{owner}/{repo}/code-scanning/default-setup",
		unmanaged = {
				"languages — drifty turns default setup on and off; which languages CodeQL scans is left to GitHub's own detection, which changes as a repository does",
				"query_suite — as above, part of how default setup runs rather than whether it is on",
				"runner_label — as above", "runner_type — as above",
				"threat_model — as above" }
)
public record CodeScanningDefaultSetupRequest(
		CodeScanningDefaultSetupResponse.State state
) {
}
