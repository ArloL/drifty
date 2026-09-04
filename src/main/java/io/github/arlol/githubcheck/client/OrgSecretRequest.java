package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code PUT /orgs/{org}/actions/secrets/{name}}. {@code
 * selectedRepositoryIds} is only meaningful when visibility is {@code
 * selected}; {@code NON_NULL} drops it otherwise instead of sending an empty
 * list GitHub would reject for any other visibility.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgSecretRequest(
		String encryptedValue,
		String keyId,
		SecretVisibility visibility,
		List<Long> selectedRepositoryIds
) {
}
