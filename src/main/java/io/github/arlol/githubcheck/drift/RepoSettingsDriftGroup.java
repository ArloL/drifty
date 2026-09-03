package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryUpdateRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

public class RepoSettingsDriftGroup extends DriftGroup {

	/**
	 * Why {@code visibility} is compared but never written. Kept as the unfixed
	 * reason so a {@code --fix} run says the setting is still drifted instead
	 * of reporting a change it never attempted.
	 */
	private static final String VISIBILITY_CHECK_ONLY = "visibility is check-only: public to private breaks forks, private to public exposes code";

	/**
	 * One managed setting: what the config wants, what GitHub has, and how to
	 * put it into a PATCH body.
	 * <p>
	 * Pairing the comparison with the write is what keeps the request to the
	 * settings that actually drifted. Building the body from the desired config
	 * instead used to send every field on every fix, so an org with
	 * {@code members_can_fork_private_repositories} disabled answered a
	 * description change with a 422 over an {@code allow_forking} value that
	 * already matched.
	 *
	 * @param write {@code null} for a setting drifty reports but does not
	 *              change, paired with {@link #unfixableReason}
	 */
	private record Setting(
			String path,
			Object wanted,
			Object got,
			Consumer<RepositoryUpdateRequest.Builder> write,
			String unfixableReason
	) {

		static Setting of(
				String path,
				Object wanted,
				Object got,
				Consumer<RepositoryUpdateRequest.Builder> write
		) {
			return new Setting(path, wanted, got, write, null);
		}

		static Setting checkOnly(
				String path,
				Object wanted,
				Object got,
				String reason
		) {
			return new Setting(path, wanted, got, null, reason);
		}

		boolean drifted() {
			return !Objects.equals(wanted, got);
		}

		boolean writable() {
			return write != null;
		}

		DriftItem item() {
			return new DriftItem.FieldMismatch(path, wanted, got);
		}

	}

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
	public Drifty.GroupName name() {
		return Drifty.GroupName.REPO_SETTINGS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		List<Setting> drifted = settings().stream()
				.filter(Setting::drifted)
				.toList();
		List<DriftItem> items = drifted.stream().map(Setting::item).toList();
		return List.of(new DriftFix(items, () -> fix(drifted)));
	}

	private List<Setting> settings() {
		var settings = new ArrayList<Setting>();
		settings.add(
				Setting.of(
						"description",
						desired.description,
						actual.description(),
						b -> b.description(desired.description)
				)
		);
		settings.add(
				Setting.of(
						"homepage_url",
						desired.homepageUrl,
						actual.homepage(),
						b -> b.homepage(desired.homepageUrl)
				)
		);
		settings.add(
				Setting.checkOnly(
						"visibility",
						PklTypes.visibility(desired.visibility),
						actual.visibility(),
						VISIBILITY_CHECK_ONLY
				)
		);
		settings.add(
				Setting.of(
						"default_branch",
						desired.defaultBranch,
						actual.defaultBranch(),
						b -> b.defaultBranch(desired.defaultBranch)
				)
		);
		settings.add(
				Setting.of(
						"has_issues",
						desired.hasIssues,
						actual.hasIssues(),
						b -> b.hasIssues(desired.hasIssues)
				)
		);
		settings.add(
				Setting.of(
						"has_projects",
						desired.hasProjects,
						actual.hasProjects(),
						b -> b.hasProjects(desired.hasProjects)
				)
		);
		settings.add(
				Setting.of(
						"has_wiki",
						desired.hasWiki,
						actual.hasWiki(),
						b -> b.hasWiki(desired.hasWiki)
				)
		);
		settings.add(
				Setting.of(
						"has_discussions",
						desired.hasDiscussions,
						actual.hasDiscussions(),
						b -> b.hasDiscussions(desired.hasDiscussions)
				)
		);
		settings.add(
				Setting.of(
						"is_template",
						desired.isTemplate,
						actual.isTemplate(),
						b -> b.isTemplate(desired.isTemplate)
				)
		);
		settings.add(
				Setting.of(
						"web_commit_signoff_required",
						desired.webCommitSignoffRequired,
						actual.webCommitSignoffRequired(),
						b -> b.webCommitSignoffRequired(
								desired.webCommitSignoffRequired
						)
				)
		);
		settings.add(
				Setting.of(
						"allow_merge_commit",
						desired.allowMergeCommit,
						actual.allowMergeCommit(),
						b -> b.allowMergeCommit(desired.allowMergeCommit)
				)
		);
		settings.add(
				Setting.of(
						"allow_squash_merge",
						desired.allowSquashMerge,
						actual.allowSquashMerge(),
						b -> b.allowSquashMerge(desired.allowSquashMerge)
				)
		);
		settings.add(
				Setting.of(
						"allow_rebase_merge",
						desired.allowRebaseMerge,
						actual.allowRebaseMerge(),
						b -> b.allowRebaseMerge(desired.allowRebaseMerge)
				)
		);
		settings.add(
				Setting.of(
						"allow_auto_merge",
						desired.allowAutoMerge,
						actual.allowAutoMerge(),
						b -> b.allowAutoMerge(desired.allowAutoMerge)
				)
		);
		settings.add(
				Setting.of(
						"allow_update_branch",
						desired.allowUpdateBranch,
						actual.allowUpdateBranch(),
						b -> b.allowUpdateBranch(desired.allowUpdateBranch)
				)
		);
		settings.add(
				Setting.of(
						"delete_branch_on_merge",
						desired.deleteBranchOnMerge,
						actual.deleteBranchOnMerge(),
						b -> b.deleteBranchOnMerge(desired.deleteBranchOnMerge)
				)
		);
		settings.add(
				Setting.of(
						"squash_merge_commit_title",
						PklTypes.squashMergeCommitTitle(
								desired.squashMergeCommitTitle
						),
						actual.squashMergeCommitTitle(),
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
						actual.squashMergeCommitMessage(),
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
						actual.mergeCommitTitle(),
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
						actual.mergeCommitMessage(),
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
							actual.allowForking(),
							b -> b.allowForking(desired.allowForking)
					)
			);
		}
		return settings;
	}

	private FixResult fix(List<Setting> drifted) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		var writable = new ArrayList<Setting>();
		for (Setting setting : drifted) {
			if (setting.writable()) {
				writable.add(setting);
			} else {
				unfixed.add(
						new FixResult.Unfixed(
								setting.item(),
								setting.unfixableReason()
						)
				);
			}
		}
		if (!writable.isEmpty()) {
			unfixed.addAll(write(writable));
		}
		return new FixResult(unfixed);
	}

	/**
	 * Writes the drifted settings in one PATCH, and on rejection works out
	 * which of them GitHub actually refused.
	 * <p>
	 * GitHub applies the fields it accepts and rejects the rest, so a 422 over
	 * one field says nothing about the others — reporting the whole request as
	 * failed told the operator the opposite of what had happened, and the
	 * changes that did land showed up as still drifted. Re-sending each field
	 * on its own settles it: a field GitHub takes is fixed (the ones the batch
	 * already applied simply repeat), and only the field that fails again is
	 * reported, with its own error as the reason. A single-field request needs
	 * no second pass, since it is already its own attribution.
	 */
	private List<FixResult.Unfixed> write(List<Setting> writable) {
		try {
			client.updateRepository(org, name, request(writable));
			return List.of();
		} catch (GitHubApiException e) {
			if (writable.size() == 1) {
				return List.of(
						new FixResult.Unfixed(
								writable.getFirst().item(),
								e.getMessage()
						)
				);
			}
			return writeIndividually(writable);
		}
	}

	private List<FixResult.Unfixed> writeIndividually(List<Setting> writable) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		for (Setting setting : writable) {
			try {
				client.updateRepository(org, name, request(List.of(setting)));
			} catch (GitHubApiException e) {
				unfixed.add(
						new FixResult.Unfixed(setting.item(), e.getMessage())
				);
			}
		}
		return unfixed;
	}

	private static RepositoryUpdateRequest request(List<Setting> settings) {
		var builder = RepositoryUpdateRequest.builder();
		settings.forEach(setting -> setting.write().accept(builder));
		return builder.build();
	}

}
