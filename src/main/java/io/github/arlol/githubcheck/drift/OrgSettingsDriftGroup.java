package io.github.arlol.githubcheck.drift;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
				builder -> client.updateOrganization(org, builder.build()),
				settings()
		).detect();
	}

	private List<Setting<OrganizationUpdateRequest.Builder>> settings() {
		var settings = new ArrayList<Setting<OrganizationUpdateRequest.Builder>>();
		settings.add(
				Setting.of(
						"name",
						desired.displayName,
						present().displayName(),
						b -> b.name(desired.displayName)
				)
		);
		settings.add(
				Setting.of(
						"description",
						desired.description,
						present().description(),
						b -> b.description(desired.description)
				)
		);
		settings.add(
				Setting.of(
						"blog",
						desired.websiteUrl,
						present().websiteUrl(),
						b -> b.blog(desired.websiteUrl)
				)
		);
		settings.add(
				Setting.of(
						"company",
						desired.company,
						present().company(),
						b -> b.company(desired.company)
				)
		);
		settings.add(
				Setting.of(
						"email",
						desired.email,
						present().email(),
						b -> b.email(desired.email)
				)
		);
		settings.add(
				Setting.of(
						"location",
						desired.location,
						present().location(),
						b -> b.location(desired.location)
				)
		);
		settings.add(
				Setting.of(
						"twitter_username",
						desired.twitterUsername,
						present().twitterUsername(),
						b -> b.twitterUsername(desired.twitterUsername)
				)
		);
		settings.add(
				Setting.of(
						"has_organization_projects",
						desired.hasOrganizationProjects,
						present().hasOrganizationProjects(),
						b -> b.hasOrganizationProjects(
								desired.hasOrganizationProjects
						)
				)
		);
		settings.add(
				Setting.of(
						"has_repository_projects",
						desired.hasRepositoryProjects,
						present().hasRepositoryProjects(),
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
						present().defaultRepositoryPermission(),
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
						present().membersCanCreateRepositories(),
						b -> b.membersCanCreateRepositories(
								desired.membersCanCreateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_public_repositories",
						desired.membersCanCreatePublicRepositories,
						present().membersCanCreatePublicRepositories(),
						b -> b.membersCanCreatePublicRepositories(
								desired.membersCanCreatePublicRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_private_repositories",
						desired.membersCanCreatePrivateRepositories,
						present().membersCanCreatePrivateRepositories(),
						b -> b.membersCanCreatePrivateRepositories(
								desired.membersCanCreatePrivateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_internal_repositories",
						desired.membersCanCreateInternalRepositories,
						present().membersCanCreateInternalRepositories(),
						b -> b.membersCanCreateInternalRepositories(
								desired.membersCanCreateInternalRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_pages",
						desired.membersCanCreatePages,
						present().membersCanCreatePages(),
						b -> b.membersCanCreatePages(
								desired.membersCanCreatePages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_public_pages",
						desired.membersCanCreatePublicPages,
						present().membersCanCreatePublicPages(),
						b -> b.membersCanCreatePublicPages(
								desired.membersCanCreatePublicPages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_private_pages",
						desired.membersCanCreatePrivatePages,
						present().membersCanCreatePrivatePages(),
						b -> b.membersCanCreatePrivatePages(
								desired.membersCanCreatePrivatePages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_fork_private_repositories",
						desired.membersCanForkPrivateRepositories,
						present().membersCanForkPrivateRepositories(),
						b -> b.membersCanForkPrivateRepositories(
								desired.membersCanForkPrivateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired,
						present().webCommitSignoffRequired(),
						b -> b.webCommitSignoffRequired(
								desired.webCommitSignoffRequired
						)
				)
		);
		settings.add(
				Setting.of(
						"deploy_keys_enabled_for_repositories",
						desired.deployKeysEnabledForRepositories,
						present().deployKeysEnabledForRepositories(),
						b -> b.deployKeysEnabledForRepositories(
								desired.deployKeysEnabledForRepositories
						)
				)
		);
		settings.add(
				Setting.checkOnly(
						"default_repository_branch",
						desired.defaultRepositoryBranch,
						present().defaultRepositoryBranch(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"two_factor_requirement_enabled",
						desired.twoFactorRequirementEnabled,
						present().twoFactorRequirementEnabled(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_delete_repositories",
						desired.membersCanDeleteRepositories,
						present().membersCanDeleteRepositories(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_change_repo_visibility",
						desired.membersCanChangeRepoVisibility,
						present().membersCanChangeRepoVisibility(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_invite_outside_collaborators",
						desired.membersCanInviteOutsideCollaborators,
						present().membersCanInviteOutsideCollaborators(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_delete_issues",
						desired.membersCanDeleteIssues,
						present().membersCanDeleteIssues(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_create_teams",
						desired.membersCanCreateTeams,
						present().membersCanCreateTeams(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_view_dependency_insights",
						desired.membersCanViewDependencyInsights,
						present().membersCanViewDependencyInsights(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"readers_can_create_discussions",
						desired.readersCanCreateDiscussions,
						present().readersCanCreateDiscussions(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"display_commenter_full_name_setting_enabled",
						desired.displayCommenterFullNameSettingEnabled,
						present().displayCommenterFullNameSettingEnabled(),
						NOT_WRITABLE
				)
		);
		return settings;
	}

}
