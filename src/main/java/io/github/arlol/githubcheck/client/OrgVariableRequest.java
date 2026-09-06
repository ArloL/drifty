package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code POST /orgs/{org}/actions/variables} and the PATCH on one
 * variable. As with {@link OrgSecretRequest}, the repository ids are only
 * carried under {@code selected} visibility.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgVariableRequest(
		String name,
		String value,
		SecretVisibility visibility,
		List<Long> selectedRepositoryIds
) {

	public OrgVariableRequest {
		selectedRepositoryIds = selectedRepositoryIds == null ? null
				: List.copyOf(selectedRepositoryIds);
	}

}
