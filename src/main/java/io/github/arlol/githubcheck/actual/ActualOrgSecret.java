package io.github.arlol.githubcheck.actual;

import java.util.List;

import io.github.arlol.githubcheck.client.SecretVisibility;

/**
 * An organization Actions secret as GitHub lists it. Values are never readable;
 * the update timestamp is what drifty compares against its recorded baseline.
 * Visibility is listed with the secret, the repository names behind a
 * {@code selected} visibility are not — they come from a second request.
 */
public record ActualOrgSecret(
		String name,
		String updatedAt,
		SecretVisibility visibility,
		List<String> selectedRepositories
) {

	public ActualOrgSecret {
		selectedRepositories = List.copyOf(selectedRepositories);
	}

}
