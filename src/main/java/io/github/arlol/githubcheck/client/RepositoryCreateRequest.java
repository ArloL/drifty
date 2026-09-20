package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = "POST /user/repos",
		unmanaged = {
				"team_id — organization-only, and this record is the body of POST /user/repos" }
)
public record RepositoryCreateRequest(
		@Nullable String name,
		@Nullable String description,
		@Nullable String homepage,
		/**
		 * {@code private} is a Java keyword, so the component cannot carry the
		 * wire name and the annotation has to. Without it SNAKE_CASE sends
		 * {@code is_private}, which GitHub does not accept and silently ignores
		 * — the repository is created public.
		 */
		@JsonProperty("private") @Nullable Boolean isPrivate,
		@Nullable Boolean hasIssues,
		@Nullable Boolean hasProjects,
		@Nullable Boolean hasWiki,
		@Nullable Boolean hasDiscussions,
		@Nullable Boolean hasDownloads,
		@Nullable Boolean isTemplate,
		@Nullable Boolean autoInit,
		@Nullable String gitignoreTemplate,
		@Nullable String licenseTemplate,
		@Nullable Boolean allowSquashMerge,
		@Nullable Boolean allowMergeCommit,
		@Nullable Boolean allowRebaseMerge,
		@Nullable Boolean allowAutoMerge,
		@Nullable Boolean deleteBranchOnMerge,
		@Nullable SquashMergeCommitTitle squashMergeCommitTitle,
		@Nullable SquashMergeCommitMessage squashMergeCommitMessage,
		@Nullable MergeCommitTitle mergeCommitTitle,
		@Nullable MergeCommitMessage mergeCommitMessage
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String name;
		private @Nullable String description;
		private @Nullable String homepage;
		private @Nullable Boolean isPrivate;
		private @Nullable Boolean hasIssues;
		private @Nullable Boolean hasProjects;
		private @Nullable Boolean hasWiki;
		private @Nullable Boolean hasDiscussions;
		private @Nullable Boolean hasDownloads;
		private @Nullable Boolean isTemplate;
		private @Nullable Boolean autoInit;
		private @Nullable String gitignoreTemplate;
		private @Nullable String licenseTemplate;
		private @Nullable Boolean allowSquashMerge;
		private @Nullable Boolean allowMergeCommit;
		private @Nullable Boolean allowRebaseMerge;
		private @Nullable Boolean allowAutoMerge;
		private @Nullable Boolean deleteBranchOnMerge;
		private @Nullable SquashMergeCommitTitle squashMergeCommitTitle;
		private @Nullable SquashMergeCommitMessage squashMergeCommitMessage;
		private @Nullable MergeCommitTitle mergeCommitTitle;
		private @Nullable MergeCommitMessage mergeCommitMessage;

		private Builder() {
		}

		public Builder name(String name) {
			this.name = name;
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

		public Builder isPrivate(Boolean isPrivate) {
			this.isPrivate = isPrivate;
			return this;
		}

		public Builder hasIssues(Boolean hasIssues) {
			this.hasIssues = hasIssues;
			return this;
		}

		public Builder hasProjects(Boolean hasProjects) {
			this.hasProjects = hasProjects;
			return this;
		}

		public Builder hasWiki(Boolean hasWiki) {
			this.hasWiki = hasWiki;
			return this;
		}

		public Builder hasDiscussions(Boolean hasDiscussions) {
			this.hasDiscussions = hasDiscussions;
			return this;
		}

		public Builder hasDownloads(Boolean hasDownloads) {
			this.hasDownloads = hasDownloads;
			return this;
		}

		public Builder isTemplate(Boolean isTemplate) {
			this.isTemplate = isTemplate;
			return this;
		}

		public Builder autoInit(Boolean autoInit) {
			this.autoInit = autoInit;
			return this;
		}

		public Builder gitignoreTemplate(String gitignoreTemplate) {
			this.gitignoreTemplate = gitignoreTemplate;
			return this;
		}

		public Builder licenseTemplate(String licenseTemplate) {
			this.licenseTemplate = licenseTemplate;
			return this;
		}

		public Builder allowSquashMerge(Boolean allowSquashMerge) {
			this.allowSquashMerge = allowSquashMerge;
			return this;
		}

		public Builder allowMergeCommit(Boolean allowMergeCommit) {
			this.allowMergeCommit = allowMergeCommit;
			return this;
		}

		public Builder allowRebaseMerge(Boolean allowRebaseMerge) {
			this.allowRebaseMerge = allowRebaseMerge;
			return this;
		}

		public Builder allowAutoMerge(Boolean allowAutoMerge) {
			this.allowAutoMerge = allowAutoMerge;
			return this;
		}

		public Builder deleteBranchOnMerge(Boolean deleteBranchOnMerge) {
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

		public RepositoryCreateRequest build() {
			return new RepositoryCreateRequest(
					name,
					description,
					homepage,
					isPrivate,
					hasIssues,
					hasProjects,
					hasWiki,
					hasDiscussions,
					hasDownloads,
					isTemplate,
					autoInit,
					gitignoreTemplate,
					licenseTemplate,
					allowSquashMerge,
					allowMergeCommit,
					allowRebaseMerge,
					allowAutoMerge,
					deleteBranchOnMerge,
					squashMergeCommitTitle,
					squashMergeCommitMessage,
					mergeCommitTitle,
					mergeCommitMessage
			);
		}

	}

}
