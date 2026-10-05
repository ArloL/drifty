package io.github.arlol.githubcheck.drift;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrganizationUpdateRequest;
import io.github.arlol.githubcheck.drift.SettingTable.Setting;
import io.github.arlol.githubcheck.pkl.Drifty;

public class OrgSettingsDriftGroup extends DriftGroup<Drifty.OrgGroupName> {

	/**
	 * Why ten of these settings are compared but never written. GitHub returns
	 * them on {@code GET /orgs/{org}} and accepts none of them on the PATCH, so
	 * reporting is all drifty can do; kept as the unfixed reason so a
	 * {@code --fix} run says the setting is still drifted instead of claiming a
	 * change it never attempted.
	 */
	private static final String NOT_WRITABLE = "cannot be changed through the API: PATCH /orgs/{org} does not accept this setting";

	private final Drifty.Organization desired;
	private final @Nullable ActualOrganization actual;
	private final GitHubClient client;
	private final String org;

	public OrgSettingsDriftGroup(
			Drifty.Organization desired,
			@Nullable ActualOrganization actual,
			GitHubClient client,
			String org
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.org = org;
	}

	/**
	 * The section this group compares. Null only for a group the config does
	 * not manage, whose read was therefore never sent — and an unmanaged group
	 * is filtered out before {@code detectDrift} runs, in
	 * {@code OrganizationChecker.createDriftGroups}. Saying so here turns a
	 * would-be NullPointerException into a named invariant.
	 */
	private ActualOrganization present() {
		return Objects.requireNonNull(actual);
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_SETTINGS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		return new SettingTable<>(
				OrganizationUpdateRequest::builder,
				builder -> ActualTypes.organization(
						client.updateOrganization(org, builder.build())
				),
				present(),
				settings()
		).detect();
	}

	private List<Setting<OrganizationUpdateRequest.Builder, ActualOrganization>> settings() {
		var settings = new ArrayList<Setting<OrganizationUpdateRequest.Builder, ActualOrganization>>();
		settings.add(
				Setting.of(
						"name",
						desired.displayName,
						ActualOrganization::displayName,
						b -> b.name(desired.displayName)
				)
		);
		settings.add(
				Setting.of(
						"description",
						desired.description,
						ActualOrganization::description,
						b -> b.description(desired.description)
				)
		);
		settings.add(
				Setting.of(
						"blog",
						desired.websiteUrl,
						ActualOrganization::websiteUrl,
						b -> b.blog(desired.websiteUrl)
				)
		);
		settings.add(
				Setting.of(
						"company",
						desired.company,
						ActualOrganization::company,
						b -> b.company(desired.company)
				)
		);
		settings.add(
				Setting.of(
						"email",
						desired.email,
						ActualOrganization::email,
						b -> b.email(desired.email)
				)
		);
		settings.add(
				Setting.of(
						"location",
						desired.location,
						ActualOrganization::location,
						b -> b.location(desired.location)
				)
		);
		settings.add(
				Setting.of(
						"twitter_username",
						desired.twitterUsername,
						ActualOrganization::twitterUsername,
						b -> b.twitterUsername(desired.twitterUsername)
				)
		);
		settings.add(
				Setting.of(
						"has_organization_projects",
						desired.hasOrganizationProjects,
						ActualOrganization::hasOrganizationProjects,
						b -> b.hasOrganizationProjects(
								desired.hasOrganizationProjects
						)
				)
		);
		settings.add(
				Setting.of(
						"has_repository_projects",
						desired.hasRepositoryProjects,
						ActualOrganization::hasRepositoryProjects,
						b -> b.hasRepositoryProjects(
								desired.hasRepositoryProjects
						)
				)
		);
		settings.add(
				Setting.of(
						"default_repository_permission",
						PklTypes.repositoryPermission(
								desired.defaultRepositoryPermission
						),
						ActualOrganization::defaultRepositoryPermission,
						b -> b.defaultRepositoryPermission(
								PklTypes.repositoryPermission(
										desired.defaultRepositoryPermission
								)
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_repositories",
						desired.membersCanCreateRepositories,
						ActualOrganization::membersCanCreateRepositories,
						b -> b.membersCanCreateRepositories(
								desired.membersCanCreateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_public_repositories",
						desired.membersCanCreatePublicRepositories,
						ActualOrganization::membersCanCreatePublicRepositories,
						b -> b.membersCanCreatePublicRepositories(
								desired.membersCanCreatePublicRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_private_repositories",
						desired.membersCanCreatePrivateRepositories,
						ActualOrganization::membersCanCreatePrivateRepositories,
						b -> b.membersCanCreatePrivateRepositories(
								desired.membersCanCreatePrivateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_internal_repositories",
						desired.membersCanCreateInternalRepositories,
						ActualOrganization::membersCanCreateInternalRepositories,
						b -> b.membersCanCreateInternalRepositories(
								desired.membersCanCreateInternalRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_pages",
						desired.membersCanCreatePages,
						ActualOrganization::membersCanCreatePages,
						b -> b.membersCanCreatePages(
								desired.membersCanCreatePages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_public_pages",
						desired.membersCanCreatePublicPages,
						ActualOrganization::membersCanCreatePublicPages,
						b -> b.membersCanCreatePublicPages(
								desired.membersCanCreatePublicPages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_private_pages",
						desired.membersCanCreatePrivatePages,
						ActualOrganization::membersCanCreatePrivatePages,
						b -> b.membersCanCreatePrivatePages(
								desired.membersCanCreatePrivatePages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_fork_private_repositories",
						desired.membersCanForkPrivateRepositories,
						ActualOrganization::membersCanForkPrivateRepositories,
						b -> b.membersCanForkPrivateRepositories(
								desired.membersCanForkPrivateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired,
						ActualOrganization::webCommitSignoffRequired,
						b -> b.webCommitSignoffRequired(
								desired.webCommitSignoffRequired
						)
				)
		);
		settings.add(
				Setting.of(
						"deploy_keys_enabled_for_repositories",
						desired.deployKeysEnabledForRepositories,
						ActualOrganization::deployKeysEnabledForRepositories,
						b -> b.deployKeysEnabledForRepositories(
								desired.deployKeysEnabledForRepositories
						)
				)
		);
		settings.add(
				Setting.checkOnly(
						"default_repository_branch",
						desired.defaultRepositoryBranch,
						ActualOrganization::defaultRepositoryBranch,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"two_factor_requirement_enabled",
						desired.twoFactorRequirementEnabled,
						ActualOrganization::twoFactorRequirementEnabled,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_delete_repositories",
						desired.membersCanDeleteRepositories,
						ActualOrganization::membersCanDeleteRepositories,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_change_repo_visibility",
						desired.membersCanChangeRepoVisibility,
						ActualOrganization::membersCanChangeRepoVisibility,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_invite_outside_collaborators",
						desired.membersCanInviteOutsideCollaborators,
						ActualOrganization::membersCanInviteOutsideCollaborators,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_delete_issues",
						desired.membersCanDeleteIssues,
						ActualOrganization::membersCanDeleteIssues,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_create_teams",
						desired.membersCanCreateTeams,
						ActualOrganization::membersCanCreateTeams,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_view_dependency_insights",
						desired.membersCanViewDependencyInsights,
						ActualOrganization::membersCanViewDependencyInsights,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"readers_can_create_discussions",
						desired.readersCanCreateDiscussions,
						ActualOrganization::readersCanCreateDiscussions,
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"display_commenter_full_name_setting_enabled",
						desired.displayCommenterFullNameSettingEnabled,
						ActualOrganization::displayCommenterFullNameSettingEnabled,
						NOT_WRITABLE
				)
		);
		return settings;
	}

}
