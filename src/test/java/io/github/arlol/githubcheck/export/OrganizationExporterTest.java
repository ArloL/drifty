package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.testsupport.Actual;

class OrganizationExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	@Test
	void anUntouchedOrganizationExportsNothing() {
		List<PklNode.Member> members = OrganizationExporter
				.settings(Actual.organization(), DEFAULTS.organization());

		assertThat(members).isEmpty();
	}

	@Test
	void aChangedSettingIsExported() {
		ActualOrganization actual = Actual.organization();
		ActualOrganization changed = new ActualOrganization(
				"Acme Inc",
				actual.description(),
				actual.websiteUrl(),
				actual.company(),
				actual.email(),
				actual.location(),
				actual.twitterUsername(),
				actual.hasOrganizationProjects(),
				actual.hasRepositoryProjects(),
				actual.defaultRepositoryPermission(),
				actual.membersCanCreateRepositories(),
				actual.membersCanCreatePublicRepositories(),
				actual.membersCanCreatePrivateRepositories(),
				actual.membersCanCreateInternalRepositories(),
				actual.membersCanCreatePages(),
				actual.membersCanCreatePublicPages(),
				actual.membersCanCreatePrivatePages(),
				actual.membersCanForkPrivateRepositories(),
				actual.webCommitSignoffRequired(),
				actual.deployKeysEnabledForRepositories(),
				actual.defaultRepositoryBranch(),
				actual.twoFactorRequirementEnabled(),
				actual.membersCanDeleteRepositories(),
				actual.membersCanChangeRepoVisibility(),
				actual.membersCanInviteOutsideCollaborators(),
				actual.membersCanDeleteIssues(),
				actual.membersCanCreateTeams(),
				actual.membersCanViewDependencyInsights(),
				actual.readersCanCreateDiscussions(),
				actual.displayCommenterFullNameSettingEnabled()
		);

		List<PklNode.Member> members = OrganizationExporter
				.settings(changed, DEFAULTS.organization());

		assertThat(members).containsExactly(
				new PklNode.Field("displayName", PklNode.Scalar.of("Acme Inc"))
		);
	}

	/**
	 * A check-only setting must still export as a field: it is what lets the
	 * value round-trip to zero drift, since {@code OrgSettingsDriftGroup} keeps
	 * comparing it forever without ever being able to fix it (see the class
	 * comment). {@code twoFactorRequirementEnabled} is true for most real
	 * organizations, which is exactly the case an omitted field would get
	 * wrong.
	 */
	@Test
	void aCheckOnlySettingIsExportedAsBothAFieldAndANote() {
		ActualOrganization actual = Actual.organization();
		ActualOrganization changed = new ActualOrganization(
				actual.displayName(),
				actual.description(),
				actual.websiteUrl(),
				actual.company(),
				actual.email(),
				actual.location(),
				actual.twitterUsername(),
				actual.hasOrganizationProjects(),
				actual.hasRepositoryProjects(),
				actual.defaultRepositoryPermission(),
				actual.membersCanCreateRepositories(),
				actual.membersCanCreatePublicRepositories(),
				actual.membersCanCreatePrivateRepositories(),
				actual.membersCanCreateInternalRepositories(),
				actual.membersCanCreatePages(),
				actual.membersCanCreatePublicPages(),
				actual.membersCanCreatePrivatePages(),
				actual.membersCanForkPrivateRepositories(),
				actual.webCommitSignoffRequired(),
				actual.deployKeysEnabledForRepositories(),
				actual.defaultRepositoryBranch(),
				true,
				actual.membersCanDeleteRepositories(),
				actual.membersCanChangeRepoVisibility(),
				actual.membersCanInviteOutsideCollaborators(),
				actual.membersCanDeleteIssues(),
				actual.membersCanCreateTeams(),
				actual.membersCanViewDependencyInsights(),
				actual.readersCanCreateDiscussions(),
				actual.displayCommenterFullNameSettingEnabled()
		);

		List<PklNode.Member> members = OrganizationExporter
				.settings(changed, DEFAULTS.organization());

		assertThat(members).containsExactly(
				new PklNode.Field(
						"twoFactorRequirementEnabled",
						PklNode.Scalar.of(true)
				),
				new PklNode.Note(
						"twoFactorRequirementEnabled is true on GitHub; drifty reports this setting but PATCH /orgs/{org} does not accept it"
				)
		);
	}

}
