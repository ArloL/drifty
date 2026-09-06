package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Body of {@code POST .../code-security/configurations/{id}/attach}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeSecurityAttachRequest(
		String scope,
		List<Long> selectedRepositoryIds
) {

	public CodeSecurityAttachRequest {
		selectedRepositoryIds = selectedRepositoryIds == null ? null
				: List.copyOf(selectedRepositoryIds);
	}

}
