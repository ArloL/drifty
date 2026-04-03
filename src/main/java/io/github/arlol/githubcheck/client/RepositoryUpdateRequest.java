package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RepositoryUpdateRequest(
		Boolean archived,
		String description,
		String homepage,
		Boolean hasIssues,
		Boolean hasProjects,
		Boolean hasWiki,
		Boolean hasDiscussions,
		Boolean isTemplate,
		Boolean allowForking,
		Boolean webCommitSignoffRequired,
		Boolean allowMergeCommit,
		Boolean allowSquashMerge,
		Boolean allowRebaseMerge,
		Boolean allowUpdateBranch,
		Boolean allowAutoMerge,
		Boolean deleteBranchOnMerge,
		String squashMergeCommitTitle,
		String squashMergeCommitMessage,
		String mergeCommitTitle,
		String mergeCommitMessage,
		String defaultBranch,
		SecurityAndAnalysis securityAndAnalysis
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private Boolean archived;
		private String description;
		private String homepage;
		private Boolean hasIssues;
		private Boolean hasProjects;
		private Boolean hasWiki;
		private Boolean hasDiscussions;
		private Boolean isTemplate;
		private Boolean allowForking;
		private Boolean webCommitSignoffRequired;
		private Boolean allowMergeCommit;
		private Boolean allowSquashMerge;
		private Boolean allowRebaseMerge;
		private Boolean allowUpdateBranch;
		private Boolean allowAutoMerge;
		private Boolean deleteBranchOnMerge;
		private String squashMergeCommitTitle;
		private String squashMergeCommitMessage;
		private String mergeCommitTitle;
		private String mergeCommitMessage;
		private String defaultBranch;
		private SecurityAndAnalysis securityAndAnalysis;

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

		public Builder squashMergeCommitTitle(String squashMergeCommitTitle) {
			this.squashMergeCommitTitle = squashMergeCommitTitle;
			return this;
		}

		public Builder squashMergeCommitMessage(
				String squashMergeCommitMessage
		) {
			this.squashMergeCommitMessage = squashMergeCommitMessage;
			return this;
		}

		public Builder mergeCommitTitle(String mergeCommitTitle) {
			this.mergeCommitTitle = mergeCommitTitle;
			return this;
		}

		public Builder mergeCommitMessage(String mergeCommitMessage) {
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
