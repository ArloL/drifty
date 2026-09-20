package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * Body of {@code PATCH /repos/{owner}/{repo}}.
 * <p>
 * Eight drift groups write this one resource — repository settings, archived,
 * and six security toggles — and each sends its own request. That only works
 * because every field here is a nullable wrapper and {@code NON_NULL} drops the
 * unset ones ({@link SecurityAndAnalysis} does the same for its toggles), so
 * each request carries just the fields its group manages and GitHub leaves the
 * rest as they are. Make a field primitive, or drop the annotation, and every
 * group's fix silently resets the others' settings.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = "PATCH /repos/{owner}/{repo}",
		unmanaged = {
				"name — renaming changes the repository's identity, and config/drifty.pkl keys repositories by name; a rename is a new entry, not drift",
				"private — visibility carries the same state plus internal, and is the field drifty compares",
				"visibility — check-only per SPEC.md: reported when it drifts and never written, because a visibility change has consequences no config file can express",
				"has_pull_requests — no drift group compares it",
				"pull_request_creation_policy — no drift group compares it",
				"use_squash_pr_title_as_default — deprecated by GitHub in favour of squash_merge_commit_title, which RepoSettingsDriftGroup does compare",
				"security_and_analysis.secret_scanning_delegated_bypass_options.reviewers.mode — SecretScanningDelegatedBypassDriftGroup compares a reviewer by id and type; GitHub added mode and nothing compares it yet" },
		undocumented = {
				"has_discussions — GitHub accepts it on this PATCH and RepoSettingsDriftGroup writes it; the 2026-03-10 schema omits it",
				"security_and_analysis.dependabot_security_updates — the same bit as /automated-security-fixes, which is the endpoint drifty writes; the PATCH schema omits it and the details response carries it",
				"security_and_analysis.secret_scanning_validity_checks — GitHub returns and accepts it; the spec omits it from this schema" }
)
public record RepositoryUpdateRequest(
		@Nullable Boolean archived,
		@Nullable String description,
		@Nullable String homepage,
		@Nullable Boolean hasIssues,
		@Nullable Boolean hasProjects,
		@Nullable Boolean hasWiki,
		@Nullable Boolean hasDiscussions,
		@Nullable Boolean isTemplate,
		@Nullable Boolean allowForking,
		@Nullable Boolean webCommitSignoffRequired,
		@Nullable Boolean allowMergeCommit,
		@Nullable Boolean allowSquashMerge,
		@Nullable Boolean allowRebaseMerge,
		@Nullable Boolean allowUpdateBranch,
		@Nullable Boolean allowAutoMerge,
		@Nullable Boolean deleteBranchOnMerge,
		@Nullable SquashMergeCommitTitle squashMergeCommitTitle,
		@Nullable SquashMergeCommitMessage squashMergeCommitMessage,
		@Nullable MergeCommitTitle mergeCommitTitle,
		@Nullable MergeCommitMessage mergeCommitMessage,
		@Nullable String defaultBranch,
		@Nullable SecurityAndAnalysis securityAndAnalysis
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable Boolean archived;
		private @Nullable String description;
		private @Nullable String homepage;
		private @Nullable Boolean hasIssues;
		private @Nullable Boolean hasProjects;
		private @Nullable Boolean hasWiki;
		private @Nullable Boolean hasDiscussions;
		private @Nullable Boolean isTemplate;
		private @Nullable Boolean allowForking;
		private @Nullable Boolean webCommitSignoffRequired;
		private @Nullable Boolean allowMergeCommit;
		private @Nullable Boolean allowSquashMerge;
		private @Nullable Boolean allowRebaseMerge;
		private @Nullable Boolean allowUpdateBranch;
		private @Nullable Boolean allowAutoMerge;
		private @Nullable Boolean deleteBranchOnMerge;
		private @Nullable SquashMergeCommitTitle squashMergeCommitTitle;
		private @Nullable SquashMergeCommitMessage squashMergeCommitMessage;
		private @Nullable MergeCommitTitle mergeCommitTitle;
		private @Nullable MergeCommitMessage mergeCommitMessage;
		private @Nullable String defaultBranch;
		private @Nullable SecurityAndAnalysis securityAndAnalysis;

		private Builder() {
		}

		public Builder archived(boolean archived) {
			this.archived = archived;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder homepage(String homepage) {
			this.homepage = homepage;
			return this;
		}

		public Builder hasIssues(boolean hasIssues) {
			this.hasIssues = hasIssues;
			return this;
		}

		public Builder hasProjects(boolean hasProjects) {
			this.hasProjects = hasProjects;
			return this;
		}

		public Builder hasWiki(boolean hasWiki) {
			this.hasWiki = hasWiki;
			return this;
		}

		public Builder hasDiscussions(boolean hasDiscussions) {
			this.hasDiscussions = hasDiscussions;
			return this;
		}

		public Builder isTemplate(boolean isTemplate) {
			this.isTemplate = isTemplate;
			return this;
		}

		public Builder allowForking(boolean allowForking) {
			this.allowForking = allowForking;
			return this;
		}

		public Builder webCommitSignoffRequired(
				boolean webCommitSignoffRequired
		) {
			this.webCommitSignoffRequired = webCommitSignoffRequired;
			return this;
		}

		public Builder allowMergeCommit(boolean allowMergeCommit) {
			this.allowMergeCommit = allowMergeCommit;
			return this;
		}

		public Builder allowSquashMerge(boolean allowSquashMerge) {
			this.allowSquashMerge = allowSquashMerge;
			return this;
		}

		public Builder allowRebaseMerge(boolean allowRebaseMerge) {
			this.allowRebaseMerge = allowRebaseMerge;
			return this;
		}

		public Builder allowUpdateBranch(boolean allowUpdateBranch) {
			this.allowUpdateBranch = allowUpdateBranch;
			return this;
		}

		public Builder allowAutoMerge(boolean allowAutoMerge) {
			this.allowAutoMerge = allowAutoMerge;
			return this;
		}

		public Builder deleteBranchOnMerge(boolean deleteBranchOnMerge) {
			this.deleteBranchOnMerge = deleteBranchOnMerge;
			return this;
		}

		public Builder squashMergeCommitTitle(
				SquashMergeCommitTitle squashMergeCommitTitle
		) {
			this.squashMergeCommitTitle = squashMergeCommitTitle;
			return this;
		}

		public Builder squashMergeCommitMessage(
				SquashMergeCommitMessage squashMergeCommitMessage
		) {
			this.squashMergeCommitMessage = squashMergeCommitMessage;
			return this;
		}

		public Builder mergeCommitTitle(MergeCommitTitle mergeCommitTitle) {
			this.mergeCommitTitle = mergeCommitTitle;
			return this;
		}

		public Builder mergeCommitMessage(
				MergeCommitMessage mergeCommitMessage
		) {
			this.mergeCommitMessage = mergeCommitMessage;
			return this;
		}

		public Builder defaultBranch(String defaultBranch) {
			this.defaultBranch = defaultBranch;
			return this;
		}

		public Builder securityAndAnalysis(
				SecurityAndAnalysis securityAndAnalysis
		) {
			this.securityAndAnalysis = securityAndAnalysis;
			return this;
		}

		public RepositoryUpdateRequest build() {
			return new RepositoryUpdateRequest(
					archived,
					description,
					homepage,
					hasIssues,
					hasProjects,
					hasWiki,
					hasDiscussions,
					isTemplate,
					allowForking,
					webCommitSignoffRequired,
					allowMergeCommit,
					allowSquashMerge,
					allowRebaseMerge,
					allowUpdateBranch,
					allowAutoMerge,
					deleteBranchOnMerge,
					squashMergeCommitTitle,
					squashMergeCommitMessage,
					mergeCommitTitle,
					mergeCommitMessage,
					defaultBranch,
					securityAndAnalysis
			);
		}

	}

}
