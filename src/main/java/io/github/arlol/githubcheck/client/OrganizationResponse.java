package io.github.arlol.githubcheck.client;

/**
 * The managed subset of {@code GET /orgs/{org}}. Every field is a nullable
 * wrapper: GitHub omits the policy flags entirely for a token without admin
 * rights, and {@code FAIL_ON_NULL_FOR_PRIMITIVES} is enabled on the client's
 * mapper, so a primitive here would throw on that response instead of reading
 * as unset.
 * <p>
 * Component order follows the spec's two tables, writable settings first,
 * matching {@code ActualOrganization} field for field.
 */
public record OrganizationResponse(
		String login,
		String name,
		String description,
		String blog,
		String company,
		String email,
		String location,
		String twitterUsername,
		Boolean hasOrganizationProjects,
		Boolean hasRepositoryProjects,
		String defaultRepositoryPermission,
		Boolean membersCanCreateRepositories,
		Boolean membersCanCreatePublicRepositories,
		Boolean membersCanCreatePrivateRepositories,
		Boolean membersCanCreateInternalRepositories,
		Boolean membersCanCreatePages,
		Boolean membersCanCreatePublicPages,
		Boolean membersCanCreatePrivatePages,
		Boolean membersCanForkPrivateRepositories,
		Boolean webCommitSignoffRequired,
		Boolean deployKeysEnabledForRepositories,
		String defaultRepositoryBranch,
		Boolean twoFactorRequirementEnabled,
		Boolean membersCanDeleteRepositories,
		Boolean membersCanChangeRepoVisibility,
		Boolean membersCanInviteOutsideCollaborators,
		Boolean membersCanDeleteIssues,
		Boolean membersCanCreateTeams,
		Boolean membersCanViewDependencyInsights,
		Boolean readersCanCreateDiscussions,
		Boolean displayCommenterFullNameSettingEnabled
) {
}
