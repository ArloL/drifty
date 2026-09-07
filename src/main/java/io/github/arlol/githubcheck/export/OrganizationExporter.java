package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * An organization's own settings, as the config lines that differ from the
 * schema's defaults.
 * <p>
 * The ten settings in {@link #checkOnly} are reported as comments rather than
 * fields: {@code GET /orgs/{org}} returns them and {@code PATCH /orgs/{org}}
 * accepts none of them, so a field would promise a {@code --fix} that cannot
 * happen — and omitting them silently would hide a real difference from the
 * reader. The same list is in {@code OrgSettingsDriftGroup}, where the
 * comparison lives.
 */
public final class OrganizationExporter {

	private static final String NOT_WRITABLE = "drifty reports this setting but PATCH /orgs/{org} does not accept it";

	private OrganizationExporter() {
	}

	public static List<PklNode.Member> settings(
			ActualOrganization actual,
			Drifty.Organization defaults
	) {
		var members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"displayName",
								actual.displayName(),
								defaults.displayName
						),
						Fields.field(
								"description",
								actual.description(),
								defaults.description
						),
						Fields.field(
								"websiteUrl",
								actual.websiteUrl(),
								defaults.websiteUrl
						),
						Fields.field(
								"company",
								actual.company(),
								defaults.company
						),
						Fields.field("email", actual.email(), defaults.email),
						Fields.field(
								"location",
								actual.location(),
								defaults.location
						),
						Fields.field(
								"twitterUsername",
								actual.twitterUsername(),
								defaults.twitterUsername
						),
						Fields.field(
								"hasOrganizationProjects",
								actual.hasOrganizationProjects(),
								defaults.hasOrganizationProjects
						),
						Fields.field(
								"hasRepositoryProjects",
								actual.hasRepositoryProjects(),
								defaults.hasRepositoryProjects
						),
						Fields.field(
								"defaultRepositoryPermission",
								actual.defaultRepositoryPermission(),
								defaults.defaultRepositoryPermission.toString()
						),
						Fields.field(
								"membersCanCreateRepositories",
								actual.membersCanCreateRepositories(),
								defaults.membersCanCreateRepositories
						),
						Fields.field(
								"membersCanCreatePublicRepositories",
								actual.membersCanCreatePublicRepositories(),
								defaults.membersCanCreatePublicRepositories
						),
						Fields.field(
								"membersCanCreatePrivateRepositories",
								actual.membersCanCreatePrivateRepositories(),
								defaults.membersCanCreatePrivateRepositories
						),
						Fields.field(
								"membersCanCreateInternalRepositories",
								actual.membersCanCreateInternalRepositories(),
								defaults.membersCanCreateInternalRepositories
						),
						Fields.field(
								"membersCanCreatePages",
								actual.membersCanCreatePages(),
								defaults.membersCanCreatePages
						),
						Fields.field(
								"membersCanCreatePublicPages",
								actual.membersCanCreatePublicPages(),
								defaults.membersCanCreatePublicPages
						),
						Fields.field(
								"membersCanCreatePrivatePages",
								actual.membersCanCreatePrivatePages(),
								defaults.membersCanCreatePrivatePages
						),
						Fields.field(
								"membersCanForkPrivateRepositories",
								actual.membersCanForkPrivateRepositories(),
								defaults.membersCanForkPrivateRepositories
						),
						Fields.field(
								"webCommitSignoffRequired",
								actual.webCommitSignoffRequired(),
								defaults.webCommitSignoffRequired
						),
						Fields.field(
								"deployKeysEnabledForRepositories",
								actual.deployKeysEnabledForRepositories(),
								defaults.deployKeysEnabledForRepositories
						)
				)
		);
		members.addAll(checkOnly(actual, defaults));
		return List.copyOf(members);
	}

	private static List<PklNode.Member> checkOnly(
			ActualOrganization actual,
			Drifty.Organization defaults
	) {
		var notes = new ArrayList<PklNode.Member>();
		note(
				notes,
				"defaultRepositoryBranch",
				actual.defaultRepositoryBranch(),
				defaults.defaultRepositoryBranch
		);
		note(
				notes,
				"twoFactorRequirementEnabled",
				actual.twoFactorRequirementEnabled(),
				defaults.twoFactorRequirementEnabled
		);
		note(
				notes,
				"membersCanDeleteRepositories",
				actual.membersCanDeleteRepositories(),
				defaults.membersCanDeleteRepositories
		);
		note(
				notes,
				"membersCanChangeRepoVisibility",
				actual.membersCanChangeRepoVisibility(),
				defaults.membersCanChangeRepoVisibility
		);
		note(
				notes,
				"membersCanInviteOutsideCollaborators",
				actual.membersCanInviteOutsideCollaborators(),
				defaults.membersCanInviteOutsideCollaborators
		);
		note(
				notes,
				"membersCanDeleteIssues",
				actual.membersCanDeleteIssues(),
				defaults.membersCanDeleteIssues
		);
		note(
				notes,
				"membersCanCreateTeams",
				actual.membersCanCreateTeams(),
				defaults.membersCanCreateTeams
		);
		note(
				notes,
				"membersCanViewDependencyInsights",
				actual.membersCanViewDependencyInsights(),
				defaults.membersCanViewDependencyInsights
		);
		note(
				notes,
				"readersCanCreateDiscussions",
				actual.readersCanCreateDiscussions(),
				defaults.readersCanCreateDiscussions
		);
		note(
				notes,
				"displayCommenterFullNameSettingEnabled",
				actual.displayCommenterFullNameSettingEnabled(),
				defaults.displayCommenterFullNameSettingEnabled
		);
		return notes;
	}

	private static void note(
			List<PklNode.Member> notes,
			String name,
			Object actual,
			Object defaultValue
	) {
		if (!Objects.equals(actual, defaultValue)) {
			notes.add(
					Fields.note(
							name + " is " + actual + " on GitHub; "
									+ NOT_WRITABLE
					)
			);
		}
	}

}
