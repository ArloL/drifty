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
@GitHubEndpoint(
		response = "GET /orgs/{org}",
		unmanaged = {
				"advanced_security_enabled_for_new_repositories — a default applied to repositories created later; drifty reconciles each repository's own security settings, which is the state that actually holds",
				"dependabot_alerts_enabled_for_new_repositories — a default for new repositories, as above",
				"dependabot_security_updates_enabled_for_new_repositories — a default for new repositories, as above",
				"dependency_graph_enabled_for_new_repositories — a default for new repositories, as above",
				"secret_scanning_enabled_for_new_repositories — a default for new repositories, as above",
				"secret_scanning_push_protection_enabled_for_new_repositories — a default for new repositories, as above",
				"secret_scanning_push_protection_custom_link — belongs with the push-protection default above and is meaningless without it",
				"billing_email — billing is not configuration drifty reconciles",
				"members_allowed_repository_creation_type — deprecated by GitHub in favour of the members_can_create_* booleans, which OrgSettingsDriftGroup does compare" }
)
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
