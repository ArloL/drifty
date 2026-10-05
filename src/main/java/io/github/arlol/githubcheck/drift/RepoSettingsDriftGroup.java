package io.github.arlol.githubcheck.drift;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.ActualTypes;
import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.drift.SettingTable.Setting;
import io.github.arlol.githubcheck.pkl.Drifty;

public class RepoSettingsDriftGroup extends DriftGroup<Drifty.GroupName> {

	/**
	 * Why {@code visibility} is compared but never written. Kept as the unfixed
	 * reason so a {@code --fix} run says the setting is still drifted instead
	 * of reporting a change it never attempted.
	 */
	private static final String VISIBILITY_CHECK_ONLY = "visibility is check-only: public to private breaks forks, private to public exposes code";

	private final Drifty.Repository desired;
	private final ActualRepository actual;
	private final @Nullable String wikiUnavailable;
	private final GitHubClient client;
	private final String org;
	private final String name;

	public RepoSettingsDriftGroup(
			Drifty.Repository desired,
			ActualRepository actual,
			GitHubClient client,
			RepoRef ref
	) {
		this(desired, actual, null, client, ref);
	}

	/**
	 * @param wikiUnavailable why GitHub offers this repository no wiki, or
	 *                        {@code null} when it does or nobody could say: a
	 *                        private repository needs a paid plan for one
	 */
	public RepoSettingsDriftGroup(
			Drifty.Repository desired,
			ActualRepository actual,
			@Nullable String wikiUnavailable,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = desired;
		this.actual = actual;
		this.wikiUnavailable = wikiUnavailable;
		this.client = client;
		this.org = ref.owner();
		this.name = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.REPO_SETTINGS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		return new SettingTable<>(
				RepositoryUpdateRequest::builder,
				builder -> ActualTypes.repository(
						client.updateRepository(org, name, builder.build())
				),
				actual,
				settings()
		).detect();
	}

	private List<Setting<RepositoryUpdateRequest.Builder, ActualRepository>> settings() {
		var settings = new ArrayList<Setting<RepositoryUpdateRequest.Builder, ActualRepository>>();
		settings.add(
				Setting.of(
						"description",
						desired.description,
						ActualRepository::description,
						b -> b.description(desired.description)
				)
		);
		settings.add(
				Setting.of(
						"homepage_url",
						desired.homepageUrl,
						ActualRepository::homepage,
						b -> b.homepage(desired.homepageUrl)
				)
		);
		settings.add(
				Setting.checkOnly(
						"visibility",
						PklTypes.visibility(desired.visibility),
						ActualRepository::visibility,
						VISIBILITY_CHECK_ONLY
				)
		);
		settings.add(
				Setting.of(
						"default_branch",
						desired.defaultBranch,
						ActualRepository::defaultBranch,
						b -> b.defaultBranch(desired.defaultBranch)
				)
		);
		settings.add(
				Setting.of(
						"has_issues",
						desired.hasIssues,
						ActualRepository::hasIssues,
						b -> b.hasIssues(desired.hasIssues)
				)
		);
		settings.add(
				Setting.of(
						"has_projects",
						desired.hasProjects,
						ActualRepository::hasProjects,
						b -> b.hasProjects(desired.hasProjects)
				)
		);
		settings.add(
				wikiUnavailable != null
						? Setting.unavailable(
								"has_wiki",
								desired.hasWiki,
								ActualRepository::hasWiki,
								wikiUnavailable
						)
						: Setting.of(
								"has_wiki",
								desired.hasWiki,
								ActualRepository::hasWiki,
								b -> b.hasWiki(desired.hasWiki)
						)
		);
		settings.add(
				Setting.of(
						"has_discussions",
						desired.hasDiscussions,
						ActualRepository::hasDiscussions,
						b -> b.hasDiscussions(desired.hasDiscussions)
				)
		);
		settings.add(
				Setting.of(
						"is_template",
						desired.isTemplate,
						ActualRepository::isTemplate,
						b -> b.isTemplate(desired.isTemplate)
				)
		);
		settings.add(
				Setting.of(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired,
						ActualRepository::webCommitSignoffRequired,
						b -> b.webCommitSignoffRequired(
								desired.webCommitSignoffRequired
						)
				)
		);
		settings.add(
				Setting.of(
						"allow_merge_commit",
						desired.allowMergeCommit,
						ActualRepository::allowMergeCommit,
						b -> b.allowMergeCommit(desired.allowMergeCommit)
				)
		);
		settings.add(
				Setting.of(
						"allow_squash_merge",
						desired.allowSquashMerge,
						ActualRepository::allowSquashMerge,
						b -> b.allowSquashMerge(desired.allowSquashMerge)
				)
		);
		settings.add(
				Setting.of(
						"allow_rebase_merge",
						desired.allowRebaseMerge,
						ActualRepository::allowRebaseMerge,
						b -> b.allowRebaseMerge(desired.allowRebaseMerge)
				)
		);
		settings.add(
				Setting.of(
						"allow_auto_merge",
						desired.allowAutoMerge,
						ActualRepository::allowAutoMerge,
						b -> b.allowAutoMerge(desired.allowAutoMerge)
				)
		);
		settings.add(
				Setting.of(
						"allow_update_branch",
						desired.allowUpdateBranch,
						ActualRepository::allowUpdateBranch,
						b -> b.allowUpdateBranch(desired.allowUpdateBranch)
				)
		);
		settings.add(
				Setting.of(
						"delete_branch_on_merge",
						desired.deleteBranchOnMerge,
						ActualRepository::deleteBranchOnMerge,
						b -> b.deleteBranchOnMerge(desired.deleteBranchOnMerge)
				)
		);
		settings.add(
				Setting.of(
						"squash_merge_commit_title",
						PklTypes.squashMergeCommitTitle(
								desired.squashMergeCommitTitle
						),
						ActualRepository::squashMergeCommitTitle,
						b -> b.squashMergeCommitTitle(
								PklTypes.squashMergeCommitTitle(
										desired.squashMergeCommitTitle
								)
						)
				)
		);
		settings.add(
				Setting.of(
						"squash_merge_commit_message",
						PklTypes.squashMergeCommitMessage(
								desired.squashMergeCommitMessage
						),
						ActualRepository::squashMergeCommitMessage,
						b -> b.squashMergeCommitMessage(
								PklTypes.squashMergeCommitMessage(
										desired.squashMergeCommitMessage
								)
						)
				)
		);
		settings.add(
				Setting.of(
						"merge_commit_title",
						PklTypes.mergeCommitTitle(desired.mergeCommitTitle),
						ActualRepository::mergeCommitTitle,
						b -> b.mergeCommitTitle(
								PklTypes.mergeCommitTitle(
										desired.mergeCommitTitle
								)
						)
				)
		);
		settings.add(
				Setting.of(
						"merge_commit_message",
						PklTypes.mergeCommitMessage(desired.mergeCommitMessage),
						ActualRepository::mergeCommitMessage,
						b -> b.mergeCommitMessage(
								PklTypes.mergeCommitMessage(
										desired.mergeCommitMessage
								)
						)
				)
		);
		if (actual.organizationOwned()) {
			// GitHub only exposes allow_forking on org-owned repositories.
			settings.add(
					Setting.of(
							"allow_forking",
							desired.allowForking,
							ActualRepository::allowForking,
							b -> b.allowForking(desired.allowForking)
					)
			);
		}
		return settings;
	}

}
