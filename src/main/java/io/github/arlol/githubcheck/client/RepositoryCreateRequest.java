package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RepositoryCreateRequest(
		String name,
		String description,
		String homepage,
		Boolean isPrivate,
		Boolean hasIssues,
		Boolean hasProjects,
		Boolean hasWiki,
		Boolean hasDiscussions,
		Boolean hasDownloads,
		Boolean isTemplate,
		Boolean autoInit,
		String gitignoreTemplate,
		String licenseTemplate,
		Boolean allowSquashMerge,
		Boolean allowMergeCommit,
		Boolean allowRebaseMerge,
		Boolean allowAutoMerge,
		Boolean deleteBranchOnMerge,
		SquashMergeCommitTitle squashMergeCommitTitle,
		SquashMergeCommitMessage squashMergeCommitMessage,
		MergeCommitTitle mergeCommitTitle,
		MergeCommitMessage mergeCommitMessage
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String name;
		private String description;
		private String homepage;
		private Boolean isPrivate;
		private Boolean hasIssues;
		private Boolean hasProjects;
		private Boolean hasWiki;
		private Boolean hasDiscussions;
		private Boolean hasDownloads;
		private Boolean isTemplate;
		private Boolean autoInit;
		private String gitignoreTemplate;
		private String licenseTemplate;
		private Boolean allowSquashMerge;
		private Boolean allowMergeCommit;
		private Boolean allowRebaseMerge;
		private Boolean allowAutoMerge;
		private Boolean deleteBranchOnMerge;
		private SquashMergeCommitTitle squashMergeCommitTitle;
		private SquashMergeCommitMessage squashMergeCommitMessage;
		private MergeCommitTitle mergeCommitTitle;
		private MergeCommitMessage mergeCommitMessage;

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
