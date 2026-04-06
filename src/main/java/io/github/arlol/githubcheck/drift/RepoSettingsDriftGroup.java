package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
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
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();
		items.addAll(
				compare(
						"description",
						desired.description(),
						Objects.toString(actual.description(), "")
				)
		);
		items.addAll(
				compare(
						"homepage_url",
						desired.homepageUrl(),
						Objects.toString(actual.homepage(), "")
				)
		);
		items.addAll(
				compare("visibility", desired.visibility(), actual.visibility())
		);
		items.addAll(
				compare(
						"default_branch",
						desired.defaultBranch(),
						actual.defaultBranch()
				)
		);
		items.addAll(
				compare("has_issues", desired.hasIssues(), actual.hasIssues())
		);
		items.addAll(
				compare(
						"has_projects",
						desired.hasProjects(),
						actual.hasProjects()
				)
		);
		items.addAll(compare("has_wiki", desired.hasWiki(), actual.hasWiki()));
		items.addAll(
				compare(
						"has_discussions",
						desired.hasDiscussions(),
						actual.hasDiscussions()
				)
		);
		items.addAll(
				compare(
						"is_template",
						desired.isTemplate(),
						actual.isTemplate()
				)
		);
		items.addAll(
				compare(
						"allow_forking",
						desired.allowForking(),
						actual.allowForking()
				)
		);
		items.addAll(
				compare(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired(),
						actual.webCommitSignoffRequired()
				)
		);
		items.addAll(
				compare(
						"allow_merge_commit",
						desired.allowMergeCommit(),
						actual.allowMergeCommit()
				)
		);
		items.addAll(
				compare(
						"allow_squash_merge",
						desired.allowSquashMerge(),
						actual.allowSquashMerge()
				)
		);
		items.addAll(
				compare(
						"allow_rebase_merge",
						desired.allowRebaseMerge(),
						actual.allowRebaseMerge()
				)
		);
		items.addAll(
				compare(
						"allow_auto_merge",
						desired.allowAutoMerge(),
						actual.allowAutoMerge()
				)
		);
		items.addAll(
				compare(
						"allow_update_branch",
						desired.allowUpdateBranch(),
						actual.allowUpdateBranch()
				)
		);
		items.addAll(
				compare(
						"delete_branch_on_merge",
						desired.deleteBranchOnMerge(),
						actual.deleteBranchOnMerge()
				)
		);
		items.addAll(
				compare(
						"squash_merge_commit_title",
						desired.squashMergeCommitTitle(),
						actual.squashMergeCommitTitle()
				)
		);
		items.addAll(
				compare(
						"squash_merge_commit_message",
						desired.squashMergeCommitMessage(),
						actual.squashMergeCommitMessage()
				)
		);
		items.addAll(
				compare(
						"merge_commit_title",
						desired.mergeCommitTitle(),
						actual.mergeCommitTitle()
				)
		);
		items.addAll(
				compare(
						"merge_commit_message",
						desired.mergeCommitMessage(),
						actual.mergeCommitMessage()
				)
		);
		return items;
	}

	@Override
	public void fix() {
		var request = RepositoryUpdateRequest.builder()
				.description(desired.description())
				.homepage(desired.homepageUrl())
				.hasIssues(desired.hasIssues())
				.hasProjects(desired.hasProjects())
				.hasWiki(desired.hasWiki())
				.hasDiscussions(desired.hasDiscussions())
				.isTemplate(desired.isTemplate())
				.allowForking(desired.allowForking())
				.webCommitSignoffRequired(desired.webCommitSignoffRequired())
				.allowMergeCommit(desired.allowMergeCommit())
				.allowSquashMerge(desired.allowSquashMerge())
				.allowRebaseMerge(desired.allowRebaseMerge())
				.allowUpdateBranch(desired.allowUpdateBranch())
				.allowAutoMerge(desired.allowAutoMerge())
				.deleteBranchOnMerge(desired.deleteBranchOnMerge())
				.squashMergeCommitTitle(desired.squashMergeCommitTitle())
				.squashMergeCommitMessage(desired.squashMergeCommitMessage())
				.mergeCommitTitle(desired.mergeCommitTitle())
				.mergeCommitMessage(desired.mergeCommitMessage())
				.defaultBranch(desired.defaultBranch())
				.build();
		client.updateRepository(org, name, request);
	}

}
