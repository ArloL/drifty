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

	@Test
	void aCheckOnlySettingBecomesANoteNotAField() {
		List<PklNode.Member> members = OrganizationExporter.settings(
				Actual.driftedOrganization(),
				DEFAULTS.organization()
		);

		assertThat(members).filteredOn(PklNode.Note.class::isInstance)
				.extracting(m -> ((PklNode.Note) m).text())
				.anySatisfy(
						text -> assertThat(text)
								.contains("PATCH /orgs/{org} does not accept")
				);
		assertThat(members).filteredOn(PklNode.Field.class::isInstance)
				.extracting(m -> ((PklNode.Field) m).name())
				.doesNotContain("twoFactorRequirementEnabled");
	}

}
