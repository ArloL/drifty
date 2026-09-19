package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET .../code-security/configurations/{id}/repositories}:
 * a repository and the state of its attachment.
 */
@GitHubEndpoint(
		response = "GET /orgs/{org}/code-security/configurations/{configuration_id}/repositories",
		unmanaged = {
				"repository.* — an attachment is compared by repository id; the repository itself is RepositoryChecker's, and checking it twice would report the same drift under two names" }
)
public record CodeSecurityRepositoryResponse(
		String status,
		Repository repository
) {

	public record Repository(
			Long id,
			String name
	) {
	}

}
