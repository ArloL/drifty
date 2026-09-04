package io.github.arlol.githubcheck.actual;

/**
 * The organization settings drifty manages, as they are on GitHub.
 * <p>
 * GitHub returns {@code null} for an unset description, blog, company, email,
 * location or twitter handle where the config says {@code ""}, and omits the
 * policy flags entirely for a token without admin rights, which reads here as
 * {@code false}. Normalising both is {@code ActualTypes}'s business, see
 * {@link io.github.arlol.githubcheck.ActualTypes#organization}; the comparison
 * sees the same vocabulary the config is written in.
 */
public record ActualOrganization(
		String displayName,
		String description,
		String websiteUrl,
		String company,
		String email,
		String location,
		String twitterUsername,
		boolean hasOrganizationProjects,
		boolean hasRepositoryProjects,
		String defaultRepositoryPermission,
		boolean membersCanCreateRepositories,
		boolean membersCanCreatePublicRepositories,
		boolean membersCanCreatePrivateRepositories,
		boolean membersCanCreateInternalRepositories,
		boolean membersCanCreatePages,
		boolean membersCanCreatePublicPages,
		boolean membersCanCreatePrivatePages,
		boolean membersCanForkPrivateRepositories,
		boolean webCommitSignoffRequired,
		boolean deployKeysEnabledForRepositories,
		String defaultRepositoryBranch,
		boolean twoFactorRequirementEnabled,
		boolean membersCanDeleteRepositories,
		boolean membersCanChangeRepoVisibility,
		boolean membersCanInviteOutsideCollaborators,
		boolean membersCanDeleteIssues,
		boolean membersCanCreateTeams,
		boolean membersCanViewDependencyInsights,
		boolean readersCanCreateDiscussions,
		boolean displayCommenterFullNameSettingEnabled
) {
}
