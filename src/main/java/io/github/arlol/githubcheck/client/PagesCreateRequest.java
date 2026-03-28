package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PagesCreateRequest(
		String buildType,
		@JsonInclude(JsonInclude.Include.NON_NULL) Source source
) {

	public record Source(
			String branch,
			String path
	) {
	}

}
