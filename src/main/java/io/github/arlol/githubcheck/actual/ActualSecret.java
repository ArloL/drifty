package io.github.arlol.githubcheck.actual;

/**
 * An Actions secret as GitHub lists it. Values are never readable; the update
 * timestamp is what drifty compares against its recorded baseline to notice a
 * secret changed behind its back.
 */
public record ActualSecret(
		String name,
		String updatedAt
) {
}
