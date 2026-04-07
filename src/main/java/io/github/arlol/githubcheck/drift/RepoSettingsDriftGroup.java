package io.github.arlol.githubcheck.drift;

import java.util.List;
import java.util.Objects;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.client.SimpleUser;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class RepoSettingsDriftGroup extends DriftGroup {

	private final RepositoryArgs desired;
	private final RepositoryDetailsResponse actual;
	private final GitHubClient client;
	private final String org;
	private final String name;

	public RepoSettingsDriftGroup(
			RepositoryArgs desired,
			RepositoryDetailsResponse actual,
			GitHubClient client,
			String org,
			String name
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.org = org;
		this.name = name;
	}

	@Override
	public String name() {
		return "repo_settings";
	}

	@Override
	public List<DriftFix> detect() {
		var items = combine(
				compare(
						"description",
						desired.description(),
						Objects.toString(actual.description(), "")
				),
				compare(
						"homepage_url",
						desired.homepageUrl(),
						Objects.toString(actual.homepage(), "")
				),
				compare(
						"visibility",
						desired.visibility(),
						actual.visibility()
				),
				compare(
						"default_branch",
						desired.defaultBranch(),
						actual.defaultBranch()
				),
				compare("has_issues", desired.hasIssues(), actual.hasIssues()),
				compare(
						"has_projects",
						desired.hasProjects(),
						actual.hasProjects()
				),
				compare("has_wiki", desired.hasWiki(), actual.hasWiki()),
				compare(
						"has_discussions",
						desired.hasDiscussions(),
						actual.hasDiscussions()
				),
				compare(
						"is_template",
						desired.isTemplate(),
						actual.isTemplate()
				),
				compare(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired(),
						actual.webCommitSignoffRequired()
				),
				compare(
						"allow_merge_commit",
						desired.allowMergeCommit(),
						actual.allowMergeCommit()
				),
				compare(
						"allow_squash_merge",
						desired.allowSquashMerge(),
						actual.allowSquashMerge()
				),
				compare(
						"allow_rebase_merge",
						desired.allowRebaseMerge(),
						actual.allowRebaseMerge()
				),
				compare(
						"allow_auto_merge",
						desired.allowAutoMerge(),
						actual.allowAutoMerge()
				),
				compare(
						"allow_update_branch",
						desired.allowUpdateBranch(),
						actual.allowUpdateBranch()
				),
				compare(
						"delete_branch_on_merge",
						desired.deleteBranchOnMerge(),
						actual.deleteBranchOnMerge()
				),
				compare(
						"squash_merge_commit_title",
						desired.squashMergeCommitTitle(),
						actual.squashMergeCommitTitle()
				),
				compare(
						"squash_merge_commit_message",
						desired.squashMergeCommitMessage(),
						actual.squashMergeCommitMessage()
				),
				compare(
						"merge_commit_title",
						desired.mergeCommitTitle(),
						actual.mergeCommitTitle()
				),
				compare(
						"merge_commit_message",
						desired.mergeCommitMessage(),
						actual.mergeCommitMessage()
				)
		);

		if (isOrgOwned()) {
			items = combine(
					items,
					compare(
							"allow_forking",
							desired.allowForking(),
							actual.allowForking()
					)
			);
		}

		return List.of(new DriftFix(items, () -> {
			var requestBuilder = RepositoryUpdateRequest.builder()
					.description(desired.description())
					.homepage(desired.homepageUrl())
					.hasIssues(desired.hasIssues())
					.hasProjects(desired.hasProjects())
					.hasWiki(desired.hasWiki())
					.hasDiscussions(desired.hasDiscussions())
					.isTemplate(desired.isTemplate())
					.webCommitSignoffRequired(
							desired.webCommitSignoffRequired()
					)
					.allowMergeCommit(desired.allowMergeCommit())
					.allowSquashMerge(desired.allowSquashMerge())
					.allowRebaseMerge(desired.allowRebaseMerge())
					.allowUpdateBranch(desired.allowUpdateBranch())
					.allowAutoMerge(desired.allowAutoMerge())
					.deleteBranchOnMerge(desired.deleteBranchOnMerge())
					.squashMergeCommitTitle(desired.squashMergeCommitTitle())
					.squashMergeCommitMessage(
							desired.squashMergeCommitMessage()
					)
					.mergeCommitTitle(desired.mergeCommitTitle())
					.mergeCommitMessage(desired.mergeCommitMessage())
					.defaultBranch(desired.defaultBranch());
			if (isOrgOwned()) {
				requestBuilder.allowForking(desired.allowForking());
			}
			client.updateRepository(org, name, requestBuilder.build());
			return FixResult.success();
		}));
	}

	private boolean isOrgOwned() {
		return actual.owner() != null
				&& actual.owner().type() == SimpleUser.UserType.ORGANIZATION;
	}

}
