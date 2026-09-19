package io.github.arlol.githubcheck.client;

@GitHubEndpoint(
		request = "PATCH /repos/{owner}/{repo}/code-scanning/default-setup"
)
public record CodeScanningDefaultSetupRequest(
		CodeScanningDefaultSetupResponse.State state
) {
}
