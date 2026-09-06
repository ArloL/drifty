package io.github.arlol.githubcheck.actual;

/**
 * A member of an organization and their role, {@code admin} or {@code member}.
 */
public record ActualOrgMember(
		String login,
		String role
) {
}
