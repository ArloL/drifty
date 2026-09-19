package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

@GitHubEndpoint(request = "POST /repos/{owner}/{repo}/pages")
public record PagesCreateRequest(
		PagesBuildType buildType,
		@JsonInclude(JsonInclude.Include.NON_NULL) Source source
) {

	public record Source(
			String branch,
			String path
	) {
	}

}
