package io.github.arlol.githubcheck.client;

/** Body of the variable POST and PATCH endpoints. */
@GitHubEndpoint(
		request = { "POST /repos/{owner}/{repo}/actions/variables",
				"PATCH /repos/{owner}/{repo}/actions/variables/{name}",
				"POST /repos/{owner}/{repo}/environments/{environment_name}/variables",
				"PATCH /repos/{owner}/{repo}/environments/{environment_name}/variables/{name}" }
)
public record VariableRequest(
		String name,
		String value
) {
}
