package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

@GitHubEndpoint(request = "POST /repos/{owner}/{repo}/pages")
public record PagesCreateRequest(
		PagesBuildType buildType,
		@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Source source
) {

	public record Source(
			String branch,
			String path
	) {
	}

}
