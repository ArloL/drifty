package io.github.arlol.githubcheck.client;

/** A repository or environment Actions variable, as GitHub lists it. */
@GitHubEndpoint(response = "GET /repos/{owner}/{repo}/actions/variables/{name}")
public record VariableResponse(
		String name,
		String value,
		String createdAt,
		String updatedAt
) {
}
