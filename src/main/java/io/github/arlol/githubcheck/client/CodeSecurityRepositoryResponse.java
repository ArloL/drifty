package io.github.arlol.githubcheck.client;

/**
 * One entry of {@code GET .../code-security/configurations/{id}/repositories}:
 * a repository and the state of its attachment.
 */
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
