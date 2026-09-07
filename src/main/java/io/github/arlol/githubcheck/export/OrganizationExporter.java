package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * An organization's own settings, as the config lines that differ from the
 * schema's defaults.
 * <p>
 * The ten settings in {@link #checkOnly} carry both a field and a comment:
 * {@code GET /orgs/{org}} returns them and {@code PATCH /orgs/{org}} accepts
 * none of them, so the field is what lets the exported value round-trip to zero
 * drift — {@code twoFactorRequirementEnabled} is true for most real
 * organizations, and without the field a fresh export would carry the schema's
 * default while GitHub carries the real value, drifting on every run thereafter
 * with no {@code --fix} able to clear it. The comment tells a reader drifty
 * will never change it. The same list is in {@code OrgSettingsDriftGroup},
 * where the comparison lives.
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

	/**
	 * Each of the ten as a field (see the class comment) immediately followed
	 * by its note, so the two never drift apart as settings are added.
	 */
	private static List<PklNode.Member> checkOnly(
			ActualOrganization actual,
			Drifty.Organization defaults
	) {
		return Fields.members(
				Fields.field(
						"defaultRepositoryBranch",
						actual.defaultRepositoryBranch(),
						defaults.defaultRepositoryBranch
				),
				checkOnlyNote(
						"defaultRepositoryBranch",
						actual.defaultRepositoryBranch(),
						defaults.defaultRepositoryBranch
				),
				Fields.field(
						"twoFactorRequirementEnabled",
						actual.twoFactorRequirementEnabled(),
						defaults.twoFactorRequirementEnabled
				),
				checkOnlyNote(
						"twoFactorRequirementEnabled",
						actual.twoFactorRequirementEnabled(),
						defaults.twoFactorRequirementEnabled
				),
				Fields.field(
						"membersCanDeleteRepositories",
						actual.membersCanDeleteRepositories(),
						defaults.membersCanDeleteRepositories
				),
				checkOnlyNote(
						"membersCanDeleteRepositories",
						actual.membersCanDeleteRepositories(),
						defaults.membersCanDeleteRepositories
				),
				Fields.field(
						"membersCanChangeRepoVisibility",
						actual.membersCanChangeRepoVisibility(),
						defaults.membersCanChangeRepoVisibility
				),
				checkOnlyNote(
						"membersCanChangeRepoVisibility",
						actual.membersCanChangeRepoVisibility(),
						defaults.membersCanChangeRepoVisibility
				),
				Fields.field(
						"membersCanInviteOutsideCollaborators",
						actual.membersCanInviteOutsideCollaborators(),
						defaults.membersCanInviteOutsideCollaborators
				),
				checkOnlyNote(
						"membersCanInviteOutsideCollaborators",
						actual.membersCanInviteOutsideCollaborators(),
						defaults.membersCanInviteOutsideCollaborators
				),
				Fields.field(
						"membersCanDeleteIssues",
						actual.membersCanDeleteIssues(),
						defaults.membersCanDeleteIssues
				),
				checkOnlyNote(
						"membersCanDeleteIssues",
						actual.membersCanDeleteIssues(),
						defaults.membersCanDeleteIssues
				),
				Fields.field(
						"membersCanCreateTeams",
						actual.membersCanCreateTeams(),
						defaults.membersCanCreateTeams
				),
				checkOnlyNote(
						"membersCanCreateTeams",
						actual.membersCanCreateTeams(),
						defaults.membersCanCreateTeams
				),
				Fields.field(
						"membersCanViewDependencyInsights",
						actual.membersCanViewDependencyInsights(),
						defaults.membersCanViewDependencyInsights
				),
				checkOnlyNote(
						"membersCanViewDependencyInsights",
						actual.membersCanViewDependencyInsights(),
						defaults.membersCanViewDependencyInsights
				),
				Fields.field(
						"readersCanCreateDiscussions",
						actual.readersCanCreateDiscussions(),
						defaults.readersCanCreateDiscussions
				),
				checkOnlyNote(
						"readersCanCreateDiscussions",
						actual.readersCanCreateDiscussions(),
						defaults.readersCanCreateDiscussions
				),
				Fields.field(
						"displayCommenterFullNameSettingEnabled",
						actual.displayCommenterFullNameSettingEnabled(),
						defaults.displayCommenterFullNameSettingEnabled
				),
				checkOnlyNote(
						"displayCommenterFullNameSettingEnabled",
						actual.displayCommenterFullNameSettingEnabled(),
						defaults.displayCommenterFullNameSettingEnabled
				)
		);
	}

	/** The note half of one check-only setting; see {@link #checkOnly}. */
	private static Optional<PklNode.Member> checkOnlyNote(
			String name,
			Object actual,
			Object defaultValue
	) {
		return Fields.note(
				name + " is " + actual + " on GitHub; " + NOT_WRITABLE,
				actual,
				defaultValue
		);
	}

}
