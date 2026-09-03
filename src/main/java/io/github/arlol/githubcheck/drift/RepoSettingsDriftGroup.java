package io.github.arlol.githubcheck.drift;

import java.util.List;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

public class RepoSettingsDriftGroup extends DriftGroup {

	private final Drifty.Repository desired;
	private final ActualRepository actual;
	private final GitHubClient client;
	private final String org;
	private final String name;

	public RepoSettingsDriftGroup(
			Drifty.Repository desired,
			ActualRepository actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.org = ref.owner();
		this.name = ref.name();
	}

	@Override
	public String name() {
		return "repo_settings";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var items = combine(
				compare(
						"description",
						desired.description,
						actual.description()
				),
				compare("homepage_url", desired.homepageUrl, actual.homepage()),
				compare(
						"visibility",
						PklTypes.visibility(desired.visibility),
						actual.visibility()
				),
				compare(
						"default_branch",
						desired.defaultBranch,
						actual.defaultBranch()
				),
				compare("has_issues", desired.hasIssues, actual.hasIssues()),
				compare(
						"has_projects",
						desired.hasProjects,
						actual.hasProjects()
				),
				compare("has_wiki", desired.hasWiki, actual.hasWiki()),
				compare(
						"has_discussions",
						desired.hasDiscussions,
						actual.hasDiscussions()
				),
				compare("is_template", desired.isTemplate, actual.isTemplate()),
				compare(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired,
						actual.webCommitSignoffRequired()
				),
				compare(
						"allow_merge_commit",
						desired.allowMergeCommit,
						actual.allowMergeCommit()
				),
				compare(
						"allow_squash_merge",
						desired.allowSquashMerge,
						actual.allowSquashMerge()
				),
				compare(
						"allow_rebase_merge",
						desired.allowRebaseMerge,
						actual.allowRebaseMerge()
				),
				compare(
						"allow_auto_merge",
						desired.allowAutoMerge,
						actual.allowAutoMerge()
				),
				compare(
						"allow_update_branch",
						desired.allowUpdateBranch,
						actual.allowUpdateBranch()
				),
				compare(
						"delete_branch_on_merge",
						desired.deleteBranchOnMerge,
						actual.deleteBranchOnMerge()
				),
				compare(
						"squash_merge_commit_title",
						PklTypes.squashMergeCommitTitle(
								desired.squashMergeCommitTitle
						),
						actual.squashMergeCommitTitle()
				),
				compare(
						"squash_merge_commit_message",
						PklTypes.squashMergeCommitMessage(
								desired.squashMergeCommitMessage
						),
						actual.squashMergeCommitMessage()
				),
				compare(
						"merge_commit_title",
						PklTypes.mergeCommitTitle(desired.mergeCommitTitle),
						actual.mergeCommitTitle()
				),
				compare(
						"merge_commit_message",
						PklTypes.mergeCommitMessage(desired.mergeCommitMessage),
						actual.mergeCommitMessage()
				)
		);

		if (actual.organizationOwned()) {
			items = combine(
					items,
					compare(
							"allow_forking",
							desired.allowForking,
							actual.allowForking()
					)
			);
		}

		return List.of(new DriftFix(items, () -> {
			var requestBuilder = RepositoryUpdateRequest.builder()
					.description(desired.description)
					.homepage(desired.homepageUrl)
					.hasIssues(desired.hasIssues)
					.hasProjects(desired.hasProjects)
					.hasWiki(desired.hasWiki)
					.hasDiscussions(desired.hasDiscussions)
					.isTemplate(desired.isTemplate)
					.webCommitSignoffRequired(desired.webCommitSignoffRequired)
					.allowMergeCommit(desired.allowMergeCommit)
					.allowSquashMerge(desired.allowSquashMerge)
					.allowRebaseMerge(desired.allowRebaseMerge)
					.allowUpdateBranch(desired.allowUpdateBranch)
					.allowAutoMerge(desired.allowAutoMerge)
					.deleteBranchOnMerge(desired.deleteBranchOnMerge)
					.squashMergeCommitTitle(
							PklTypes.squashMergeCommitTitle(
									desired.squashMergeCommitTitle
							)
					)
					.squashMergeCommitMessage(
							PklTypes.squashMergeCommitMessage(
									desired.squashMergeCommitMessage
							)
					)
					.mergeCommitTitle(
							PklTypes.mergeCommitTitle(desired.mergeCommitTitle)
					)
					.mergeCommitMessage(
							PklTypes.mergeCommitMessage(
									desired.mergeCommitMessage
							)
					)
					.defaultBranch(desired.defaultBranch);
			if (actual.organizationOwned()) {
				requestBuilder.allowForking(desired.allowForking);
			}
			client.updateRepository(org, name, requestBuilder.build());
			return FixResult.success();
		}));
	}

}
