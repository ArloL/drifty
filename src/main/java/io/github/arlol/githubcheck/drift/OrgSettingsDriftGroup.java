package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrganizationUpdateRequest;
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

	/**
	 * One managed setting: what the config wants, what GitHub has, and how to
	 * put it into a PATCH body.
	 * <p>
	 * Pairing the comparison with the write is what keeps the request to the
	 * settings that actually drifted. Building the body from the desired config
	 * instead would send every writable field on every fix, and
	 * {@code members_can_create_internal_repositories} is a 422 on any
	 * organization outside GitHub Enterprise — even when it already holds the
	 * wanted value — so a description change would fail over a setting that had
	 * not drifted.
	 *
	 * @param write {@code null} for a setting drifty reports but does not
	 *              change, paired with {@link #unfixableReason}
	 */
	private record Setting(
			String path,
			Object wanted,
			Object got,
			Consumer<OrganizationUpdateRequest.Builder> write,
			String unfixableReason
	) {

		static Setting of(
				String path,
				Object wanted,
				Object got,
				Consumer<OrganizationUpdateRequest.Builder> write
		) {
			return new Setting(path, wanted, got, write, null);
		}

		static Setting checkOnly(
				String path,
				Object wanted,
				Object got,
				String reason
		) {
			return new Setting(path, wanted, got, null, reason);
		}

		boolean drifted() {
			return !Objects.equals(wanted, got);
		}

		boolean writable() {
			return write != null;
		}

		DriftItem item() {
			return new DriftItem.FieldMismatch(path, wanted, got);
		}

	}

	private final Drifty.Organization desired;
	private final ActualOrganization actual;
	private final GitHubClient client;
	private final String org;

	public OrgSettingsDriftGroup(
			Drifty.Organization desired,
			ActualOrganization actual,
			GitHubClient client,
			String org
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_SETTINGS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		List<Setting> drifted = settings().stream()
				.filter(Setting::drifted)
				.toList();
		List<DriftItem> items = drifted.stream().map(Setting::item).toList();
		return List.of(new DriftFix(items, () -> fix(drifted)));
	}

	private List<Setting> settings() {
		var settings = new ArrayList<Setting>();
		settings.add(
				Setting.of(
						"name",
						desired.displayName,
						actual.displayName(),
						b -> b.name(desired.displayName)
				)
		);
		settings.add(
				Setting.of(
						"description",
						desired.description,
						actual.description(),
						b -> b.description(desired.description)
				)
		);
		settings.add(
				Setting.of(
						"blog",
						desired.websiteUrl,
						actual.websiteUrl(),
						b -> b.blog(desired.websiteUrl)
				)
		);
		settings.add(
				Setting.of(
						"company",
						desired.company,
						actual.company(),
						b -> b.company(desired.company)
				)
		);
		settings.add(
				Setting.of(
						"email",
						desired.email,
						actual.email(),
						b -> b.email(desired.email)
				)
		);
		settings.add(
				Setting.of(
						"location",
						desired.location,
						actual.location(),
						b -> b.location(desired.location)
				)
		);
		settings.add(
				Setting.of(
						"twitter_username",
						desired.twitterUsername,
						actual.twitterUsername(),
						b -> b.twitterUsername(desired.twitterUsername)
				)
		);
		settings.add(
				Setting.of(
						"has_organization_projects",
						desired.hasOrganizationProjects,
						actual.hasOrganizationProjects(),
						b -> b.hasOrganizationProjects(
								desired.hasOrganizationProjects
						)
				)
		);
		settings.add(
				Setting.of(
						"has_repository_projects",
						desired.hasRepositoryProjects,
						actual.hasRepositoryProjects(),
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
						actual.defaultRepositoryPermission(),
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
						actual.membersCanCreateRepositories(),
						b -> b.membersCanCreateRepositories(
								desired.membersCanCreateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_public_repositories",
						desired.membersCanCreatePublicRepositories,
						actual.membersCanCreatePublicRepositories(),
						b -> b.membersCanCreatePublicRepositories(
								desired.membersCanCreatePublicRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_private_repositories",
						desired.membersCanCreatePrivateRepositories,
						actual.membersCanCreatePrivateRepositories(),
						b -> b.membersCanCreatePrivateRepositories(
								desired.membersCanCreatePrivateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_internal_repositories",
						desired.membersCanCreateInternalRepositories,
						actual.membersCanCreateInternalRepositories(),
						b -> b.membersCanCreateInternalRepositories(
								desired.membersCanCreateInternalRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_pages",
						desired.membersCanCreatePages,
						actual.membersCanCreatePages(),
						b -> b.membersCanCreatePages(
								desired.membersCanCreatePages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_public_pages",
						desired.membersCanCreatePublicPages,
						actual.membersCanCreatePublicPages(),
						b -> b.membersCanCreatePublicPages(
								desired.membersCanCreatePublicPages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_create_private_pages",
						desired.membersCanCreatePrivatePages,
						actual.membersCanCreatePrivatePages(),
						b -> b.membersCanCreatePrivatePages(
								desired.membersCanCreatePrivatePages
						)
				)
		);
		settings.add(
				Setting.of(
						"members_can_fork_private_repositories",
						desired.membersCanForkPrivateRepositories,
						actual.membersCanForkPrivateRepositories(),
						b -> b.membersCanForkPrivateRepositories(
								desired.membersCanForkPrivateRepositories
						)
				)
		);
		settings.add(
				Setting.of(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired,
						actual.webCommitSignoffRequired(),
						b -> b.webCommitSignoffRequired(
								desired.webCommitSignoffRequired
						)
				)
		);
		settings.add(
				Setting.of(
						"deploy_keys_enabled_for_repositories",
						desired.deployKeysEnabledForRepositories,
						actual.deployKeysEnabledForRepositories(),
						b -> b.deployKeysEnabledForRepositories(
								desired.deployKeysEnabledForRepositories
						)
				)
		);
		settings.add(
				Setting.checkOnly(
						"default_repository_branch",
						desired.defaultRepositoryBranch,
						actual.defaultRepositoryBranch(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"two_factor_requirement_enabled",
						desired.twoFactorRequirementEnabled,
						actual.twoFactorRequirementEnabled(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_delete_repositories",
						desired.membersCanDeleteRepositories,
						actual.membersCanDeleteRepositories(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_change_repo_visibility",
						desired.membersCanChangeRepoVisibility,
						actual.membersCanChangeRepoVisibility(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_invite_outside_collaborators",
						desired.membersCanInviteOutsideCollaborators,
						actual.membersCanInviteOutsideCollaborators(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_delete_issues",
						desired.membersCanDeleteIssues,
						actual.membersCanDeleteIssues(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_create_teams",
						desired.membersCanCreateTeams,
						actual.membersCanCreateTeams(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"members_can_view_dependency_insights",
						desired.membersCanViewDependencyInsights,
						actual.membersCanViewDependencyInsights(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"readers_can_create_discussions",
						desired.readersCanCreateDiscussions,
						actual.readersCanCreateDiscussions(),
						NOT_WRITABLE
				)
		);
		settings.add(
				Setting.checkOnly(
						"display_commenter_full_name_setting_enabled",
						desired.displayCommenterFullNameSettingEnabled,
						actual.displayCommenterFullNameSettingEnabled(),
						NOT_WRITABLE
				)
		);
		return settings;
	}

	private FixResult fix(List<Setting> drifted) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		var writable = new ArrayList<Setting>();
		for (Setting setting : drifted) {
			if (setting.writable()) {
				writable.add(setting);
			} else {
				unfixed.add(
						new FixResult.Unfixed(
								setting.item(),
								setting.unfixableReason()
						)
				);
			}
		}
		if (!writable.isEmpty()) {
			unfixed.addAll(write(writable));
		}
		return new FixResult(unfixed);
	}

	/**
	 * Writes the drifted settings in one PATCH, and on rejection works out
	 * which of them GitHub actually refused.
	 * <p>
	 * GitHub applies the fields it accepts and rejects the rest, so a 422 over
	 * one field says nothing about the others — reporting the whole request as
	 * failed would tell the operator the opposite of what had happened, and the
	 * changes that did land would show up as still drifted. Re-sending each
	 * field on its own settles it: a field GitHub takes is fixed (the ones the
	 * batch already applied simply repeat), and only the field that fails again
	 * is reported, with its own error as the reason. A single-field request
	 * needs no second pass, since it is already its own attribution.
	 */
	private List<FixResult.Unfixed> write(List<Setting> writable) {
		try {
			client.updateOrganization(org, request(writable));
			return List.of();
		} catch (GitHubApiException e) {
			if (writable.size() == 1) {
				return List.of(
						new FixResult.Unfixed(
								writable.getFirst().item(),
								e.getMessage()
						)
				);
			}
			return writeIndividually(writable);
		}
	}

	private List<FixResult.Unfixed> writeIndividually(List<Setting> writable) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		for (Setting setting : writable) {
			try {
				client.updateOrganization(org, request(List.of(setting)));
			} catch (GitHubApiException e) {
				unfixed.add(
						new FixResult.Unfixed(setting.item(), e.getMessage())
				);
			}
		}
		return unfixed;
	}

	private static OrganizationUpdateRequest request(List<Setting> settings) {
		var builder = OrganizationUpdateRequest.builder();
		settings.forEach(setting -> setting.write().accept(builder));
		return builder.build();
	}

}
