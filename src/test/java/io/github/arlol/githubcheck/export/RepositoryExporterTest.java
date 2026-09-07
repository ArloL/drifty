package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.RepositoryState;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;

class RepositoryExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	/**
	 * GitHub's defaults for a freshly created, organization-owned repository.
	 */
	private static ActualRepository defaultRepository() {
		return new ActualRepository(
				false,
				true,
				"",
				"",
				RepositoryVisibility.PUBLIC,
				"main",
				List.of(),
				true,
				true,
				true,
				false,
				false,
				true,
				false,
				true,
				true,
				true,
				false,
				false,
				false,
				SquashMergeCommitTitle.COMMIT_OR_PR_TITLE,
				SquashMergeCommitMessage.COMMIT_MESSAGES,
				MergeCommitTitle.MERGE_MESSAGE,
				MergeCommitMessage.PR_TITLE
		);
	}

	private static ActualSecurityAndAnalysis defaultSecurityAndAnalysis() {
		return new ActualSecurityAndAnalysis(
				true,
				true,
				false,
				false,
				false,
				false,
				false,
				false,
				List.of()
		);
	}

	private static ActualWorkflowPermissions defaultWorkflowPermissions() {
		return new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.WRITE,
				true
		);
	}

	private static RepositoryState state(
			ActualRepository repository,
			ActualSecurityAndAnalysis securityAndAnalysis,
			boolean vulnerabilityAlerts,
			boolean automatedSecurityFixes,
			boolean immutableReleases,
			boolean privateVulnerabilityReporting,
			boolean codeScanningDefaultSetup,
			ActualWorkflowPermissions workflowPermissions
	) {
		return new RepositoryState(
				new RepoRef("acme", "api"),
				repository,
				securityAndAnalysis,
				vulnerabilityAlerts,
				automatedSecurityFixes,
				immutableReleases,
				privateVulnerabilityReporting,
				codeScanningDefaultSetup,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				workflowPermissions,
				Optional.empty(),
				List.of(),
				Map.of(),
				List.of(),
				List.of(),
				null
		);
	}

	/**
	 * A repository at GitHub's defaults, at rest on every field this exporter
	 * covers.
	 */
	private static RepositoryState defaultState() {
		return state(
				defaultRepository(),
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);
	}

	@Test
	void aRepositoryAtGitHubsDefaultsExportsOnlyName() {
		PklNode entry = RepositoryExporter.entry(defaultState(), DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

	@Test
	void aChangedMergeSettingExportsNameAndThatSetting() {
		ActualRepository base = defaultRepository();
		ActualRepository changed = new ActualRepository(
				base.archived(),
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				SquashMergeCommitTitle.PR_TITLE,
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				changed,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				squashMergeCommitTitle = "PR_TITLE"
				""");
	}

	@Test
	void anArchivedRepositoryExportsArchivedAndTheNoteWithNoSecurityFields() {
		ActualRepository base = defaultRepository();
		ActualRepository archived = new ActualRepository(
				true,
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		// Values that differ from every default, so a broken archived guard
		// would leak them as real drift instead of staying silent — not
		// values the checker actually failed to read: security_and_analysis
		// and workflow permissions are fetched regardless of archived state,
		// they are just never compared once archived = true (see the class
		// comment).
		ActualSecurityAndAnalysis driftedSecurity = new ActualSecurityAndAnalysis(
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				List.of()
		);
		ActualWorkflowPermissions driftedWorkflowPermissions = new ActualWorkflowPermissions(
				DefaultWorkflowPermissions.READ,
				false
		);
		RepositoryState state = state(
				archived,
				driftedSecurity,
				false,
				false,
				false,
				false,
				false,
				driftedWorkflowPermissions
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						archived = true
						// drifty checks only archived on an archived repository, so its other settings
						// are neither compared nor exported
						"""
		);
	}

	@Test
	void aPrivateRepositoryExportsVisibilityAsAFieldAndANote() {
		ActualRepository base = defaultRepository();
		ActualRepository priv = new ActualRepository(
				base.archived(),
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				RepositoryVisibility.PRIVATE,
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				priv,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo(
				"""
						name = "api"
						visibility = "private"
						// visibility is private on GitHub; drifty reports it but never changes it:
						// public to private breaks forks, private to public exposes code
						"""
		);
	}

	@Test
	void allowForkingIsOmittedForANonOrganizationOwnedRepository() {
		ActualRepository base = defaultRepository();
		// RepoSettingsDriftGroup only compares allow_forking on an
		// organization-owned repository, so a personal repository's export
		// must not invent that comparison even when the value differs.
		ActualRepository personal = new ActualRepository(
				base.archived(),
				false,
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				base.topics(),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				false,
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				personal,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

	@Test
	void topicsAreEmittedSorted() {
		ActualRepository base = defaultRepository();
		ActualRepository withTopics = new ActualRepository(
				base.archived(),
				base.organizationOwned(),
				base.description(),
				base.homepage(),
				base.visibility(),
				base.defaultBranch(),
				List.of("zebra", "alpha"),
				base.hasIssues(),
				base.hasProjects(),
				base.hasWiki(),
				base.hasDiscussions(),
				base.isTemplate(),
				base.allowForking(),
				base.webCommitSignoffRequired(),
				base.allowMergeCommit(),
				base.allowSquashMerge(),
				base.allowRebaseMerge(),
				base.allowAutoMerge(),
				base.allowUpdateBranch(),
				base.deleteBranchOnMerge(),
				base.squashMergeCommitTitle(),
				base.squashMergeCommitMessage(),
				base.mergeCommitTitle(),
				base.mergeCommitMessage()
		);
		RepositoryState state = state(
				withTopics,
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				topics {
				  "alpha"
				  "zebra"
				}
				""");
	}

	@Test
	void secretScanningDelegatedBypassReviewersAreEmittedSorted() {
		ActualSecurityAndAnalysis security = new ActualSecurityAndAnalysis(
				true,
				true,
				false,
				false,
				false,
				false,
				false,
				true,
				List.of(
						new BypassReviewer("TEAM", 2L),
						new BypassReviewer("ROLE", 1L)
				)
		);
		RepositoryState state = state(
				defaultRepository(),
				security,
				true,
				false,
				false,
				false,
				false,
				defaultWorkflowPermissions()
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				secretScanningDelegatedBypass = true
				secretScanningDelegatedBypassReviewers {
				  new {
				    reviewerId = 1
				    reviewerType = "ROLE"
				  }
				  new {
				    reviewerId = 2
				    reviewerType = "TEAM"
				  }
				}
				""");
	}

	@Test
	void workflowPermissionsAreExportedWhenTheyDiffer() {
		RepositoryState state = state(
				defaultRepository(),
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.READ,
						false
				)
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				defaultWorkflowPermissions = "read"
				canApprovePullRequestReviews = false
				""");
	}

	@Test
	void nullWorkflowPermissionsAreGuardedNotDereferenced() {
		RepositoryState state = state(
				defaultRepository(),
				defaultSecurityAndAnalysis(),
				true,
				false,
				false,
				false,
				false,
				null
		);

		PklNode entry = RepositoryExporter.entry(state, DEFAULTS);

		assertThat(PklWriter.write(entry)).isEqualTo("""
				name = "api"
				""");
	}

}
