package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

public record PagesUpdateRequest(
		String buildType,
		@JsonInclude(JsonInclude.Include.NON_NULL) Source source,
		boolean httpsEnforced
) {

	public record Source(
			String branch,
			String path
	) {
	}

}
