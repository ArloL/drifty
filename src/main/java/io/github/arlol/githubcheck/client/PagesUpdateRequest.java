package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
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
