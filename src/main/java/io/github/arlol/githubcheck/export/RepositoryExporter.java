package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.arlol.githubcheck.RepositoryState;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis.BypassReviewer;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * One repository's own settings, as a {@code new { … }} element of the
 * {@code repositories} listing.
 * <p>
 * {@code visibility} carries both a field and a note, for the reason
 * {@code OrganizationExporter}'s ten check-only settings do: {@code
 * RepoSettingsDriftGroup} compares it but never writes it, so leaving out the
 * field would carry the schema's default forever while GitHub carries the real
 * value — a config that started at zero drift would drift on the very next run,
 * with no {@code --fix} able to clear it. The field closes that gap; the note
 * tells a developer drifty will never act on it.
 * <p>
 * {@code allowForking} is exported only when
 * {@link ActualRepository#organizationOwned()}: {@code RepoSettingsDriftGroup}
 * only compares it there too, because GitHub exposes the setting solely on an
 * organization-owned repository, and an export must not invent a comparison the
 * checker itself skips.
 * <p>
 * An archived repository skips the whole security block below
 * {@code mergeCommitMessage} in favour of one note. Its five endpoint-fed
 * booleans (vulnerability alerts, automated fixes, immutable releases, private
 * vulnerability reporting, code scanning default setup) read false because
 * {@code RepositoryChecker} does not fetch them for an archived repository at
 * all — not because they are off. Its {@code security_and_analysis} toggles and
 * workflow permissions, by contrast, are fetched regardless of archived state;
 * they are folded into the same guard because once a config sets
 * {@code archived = true}, {@code RepositoryChecker.createDriftGroups} runs
 * only {@code ArchivedDriftGroup} and skips every other group, so none of these
 * settings would ever be compared against this file again — exporting them
 * would add fields nothing acts on.
 */
public final class RepositoryExporter {

	private static final String VISIBILITY_NOT_WRITABLE = "drifty reports it but never changes it: public to private breaks forks, private to public exposes code";

	private static final String ARCHIVED_NOTE = "drifty checks only archived on an archived repository, so its other settings are neither compared nor exported";

	private RepositoryExporter() {
	}

	public static PklNode entry(
			RepositoryState state,
			SchemaDefaults defaults
	) {
		ActualRepository actual = state.repository();
		Drifty.Repository base = defaults.repository();

		var members = new ArrayList<PklNode.Member>();
		members.add(Fields.required("name", state.name()).orElseThrow());
		members.addAll(repositoryMembers(actual, base));
		if (actual.archived()) {
			members.add(Fields.note(ARCHIVED_NOTE));
		} else {
			members.addAll(securityMembers(state, base));
		}
		return new PklNode.Obj(members);
	}

	private static List<PklNode.Member> repositoryMembers(
			ActualRepository actual,
			Drifty.Repository base
	) {
		String visibility = wire(actual.visibility());
		return Fields.members(
				Fields.field("archived", actual.archived(), base.archived),
				Fields.field(
						"description",
						actual.description(),
						base.description
				),
				Fields.field(
						"homepageUrl",
						actual.homepage(),
						base.homepageUrl
				),
				Fields.field(
						"visibility",
						visibility,
						base.visibility.toString()
				),
				Fields.note(
						"visibility is " + visibility + " on GitHub; "
								+ VISIBILITY_NOT_WRITABLE,
						visibility,
						base.visibility.toString()
				),
				Fields.field(
						"defaultBranch",
						actual.defaultBranch(),
						base.defaultBranch
				),
				Fields.strings("topics", actual.topics(), base.topics),
				Fields.field("hasIssues", actual.hasIssues(), base.hasIssues),
				Fields.field(
						"hasProjects",
						actual.hasProjects(),
						base.hasProjects
				),
				Fields.field("hasWiki", actual.hasWiki(), base.hasWiki),
				Fields.field(
						"hasDiscussions",
						actual.hasDiscussions(),
						base.hasDiscussions
				),
				Fields.field(
						"isTemplate",
						actual.isTemplate(),
						base.isTemplate
				),
				actual.organizationOwned() ? Fields.field(
						"allowForking",
						actual.allowForking(),
						base.allowForking
				) : Optional.empty(),
				Fields.field(
						"webCommitSignoffRequired",
						actual.webCommitSignoffRequired(),
						base.webCommitSignoffRequired
				),
				Fields.field(
						"allowMergeCommit",
						actual.allowMergeCommit(),
						base.allowMergeCommit
				),
				Fields.field(
						"allowSquashMerge",
						actual.allowSquashMerge(),
						base.allowSquashMerge
				),
				Fields.field(
						"allowRebaseMerge",
						actual.allowRebaseMerge(),
						base.allowRebaseMerge
				),
				Fields.field(
						"allowAutoMerge",
						actual.allowAutoMerge(),
						base.allowAutoMerge
				),
				Fields.field(
						"allowUpdateBranch",
						actual.allowUpdateBranch(),
						base.allowUpdateBranch
				),
				Fields.field(
						"deleteBranchOnMerge",
						actual.deleteBranchOnMerge(),
						base.deleteBranchOnMerge
				),
				Fields.field(
						"squashMergeCommitTitle",
						actual.squashMergeCommitTitle(),
						base.squashMergeCommitTitle.toString()
				),
				Fields.field(
						"squashMergeCommitMessage",
						actual.squashMergeCommitMessage(),
						base.squashMergeCommitMessage.toString()
				),
				Fields.field(
						"mergeCommitTitle",
						actual.mergeCommitTitle(),
						base.mergeCommitTitle.toString()
				),
				Fields.field(
						"mergeCommitMessage",
						actual.mergeCommitMessage(),
						base.mergeCommitMessage.toString()
				)
		);
	}

	/**
	 * Only called for a repository that is not archived; see the class comment
	 * for why an archived one skips this entirely.
	 */
	private static List<PklNode.Member> securityMembers(
			RepositoryState state,
			Drifty.Repository base
	) {
		ActualSecurityAndAnalysis sa = state.securityAndAnalysis();
		var members = new ArrayList<>(
				Fields.members(
						Fields.field(
								"vulnerabilityAlerts",
								state.vulnerabilityAlerts(),
								base.vulnerabilityAlerts
						),
						Fields.field(
								"automatedSecurityFixes",
								state.automatedSecurityFixes(),
								base.automatedSecurityFixes
						),
						Fields.field(
								"immutableReleases",
								state.immutableReleases(),
								base.immutableReleases
						),
						Fields.field(
								"privateVulnerabilityReporting",
								state.privateVulnerabilityReporting(),
								base.privateVulnerabilityReporting
						),
						Fields.field(
								"codeScanningDefaultSetup",
								state.codeScanningDefaultSetup(),
								base.codeScanningDefaultSetup
						),
						Fields.field(
								"secretScanning",
								sa.secretScanning(),
								base.secretScanning
						),
						Fields.field(
								"secretScanningPushProtection",
								sa.secretScanningPushProtection(),
								base.secretScanningPushProtection
						),
						Fields.field(
								"secretScanningValidityChecks",
								sa.secretScanningValidityChecks(),
								base.secretScanningValidityChecks
						),
						Fields.field(
								"secretScanningNonProviderPatterns",
								sa.secretScanningNonProviderPatterns(),
								base.secretScanningNonProviderPatterns
						),
						Fields.field(
								"advancedSecurity",
								sa.advancedSecurity(),
								base.advancedSecurity
						),
						Fields.field(
								"secretScanningAiDetection",
								sa.secretScanningAiDetection(),
								base.secretScanningAiDetection
						),
						Fields.field(
								"secretScanningDelegatedAlertDismissal",
								sa.secretScanningDelegatedAlertDismissal(),
								base.secretScanningDelegatedAlertDismissal
						),
						Fields.field(
								"secretScanningDelegatedBypass",
								sa.secretScanningDelegatedBypass(),
								base.secretScanningDelegatedBypass
						)
				)
		);
		Fields.objects(
				"secretScanningDelegatedBypassReviewers",
				bypassReviewers(sa.bypassReviewers())
		).ifPresent(members::add);
		if (state.workflowPermissions() != null) {
			members.addAll(
					workflowPermissionsMembers(
							state.workflowPermissions(),
							base
					)
			);
		}
		return members;
	}

	/**
	 * No schema default exists for a single reviewer's fields, mirroring
	 * {@code CodeSecurityConfigurationExporter}'s reviewers: the drift group
	 * compares the whole reviewer as one string, not field by field, so there
	 * is nothing to diff against here either.
	 */
	private static List<PklNode> bypassReviewers(
			List<BypassReviewer> reviewers
	) {
		return reviewers.stream()
				.sorted(Comparator.comparing(BypassReviewer::toString))
				.<PklNode>map(
						reviewer -> new PklNode.Obj(
								List.of(
										required(
												"reviewerId",
												reviewer.reviewerId()
										),
										required(
												"reviewerType",
												reviewer.reviewerType()
										)
								)
						)
				)
				.toList();
	}

	private static List<PklNode.Member> workflowPermissionsMembers(
			ActualWorkflowPermissions actual,
			Drifty.Repository base
	) {
		return Fields.members(
				Fields.field(
						"defaultWorkflowPermissions",
						AccountExporter
								.wire(actual.defaultWorkflowPermissions()),
						base.defaultWorkflowPermissions.toString()
				),
				Fields.field(
						"canApprovePullRequestReviews",
						actual.canApprovePullRequestReviews(),
						base.canApprovePullRequestReviews
				)
		);
	}

	private static PklNode.Member required(String name, long value) {
		return Fields.required(name, value).orElseThrow();
	}

	private static PklNode.Member required(String name, String value) {
		return Fields.required(name, value).orElseThrow();
	}

	/**
	 * GitHub's visibility spells its constants upper-case
	 * ({@link RepositoryVisibility#PUBLIC}); the schema spells the same union
	 * lower-case ({@code Drifty.Visibility.PUBLIC.toString()} is
	 * {@code "public"}). Comparing the two as enums would compare like with
	 * unlike, so this maps to the schema's own spelling first.
	 */
	private static String wire(RepositoryVisibility value) {
		return switch (value) {
		case PUBLIC -> "public";
		case PRIVATE -> "private";
		case INTERNAL -> "internal";
		};
	}

}
