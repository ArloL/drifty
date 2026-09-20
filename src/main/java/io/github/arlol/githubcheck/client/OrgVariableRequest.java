package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /orgs/{org}/actions/variables} and the PATCH on one
 * variable. As with {@link OrgSecretRequest}, the repository ids are only
 * carried under {@code selected} visibility.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = { "POST /orgs/{org}/actions/variables",
				"PATCH /orgs/{org}/actions/variables/{name}" }
)
public record OrgVariableRequest(
		String name,
		String value,
		SecretVisibility visibility,
		@Nullable List<Long> selectedRepositoryIds
) {

	public OrgVariableRequest {
		selectedRepositoryIds = selectedRepositoryIds == null ? null
				: List.copyOf(selectedRepositoryIds);
	}

}
