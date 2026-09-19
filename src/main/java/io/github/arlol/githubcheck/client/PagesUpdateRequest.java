package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = "PUT /repos/{owner}/{repo}/pages",
		unmanaged = {
				"cname — a custom domain is DNS state as much as repository state, and PagesDriftGroup does not compare it" }
)
public record PagesUpdateRequest(
		PagesBuildType buildType,
		Source source,
		Boolean httpsEnforced
) {

	public record Source(
			String branch,
			String path
	) {
	}

}
