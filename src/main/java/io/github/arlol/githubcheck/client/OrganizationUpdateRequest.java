package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code PATCH /orgs/{org}}.
 * <p>
 * The organization settings drift group sends only the fields that drifted, one
 * PATCH covering however many of the twenty writable settings changed. That
 * only works because every field here is a nullable wrapper and
 * {@code NON_NULL} drops the unset ones, so a request carries just the settings
 * this run is fixing and GitHub leaves the rest as they are. Make a field
 * primitive, or drop the annotation, and a description change would PATCH every
 * writable setting back to its config value on every fix run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrganizationUpdateRequest(
		String name,
		String description,
		String blog,
		String company,
		String email,
		String location,
		String twitterUsername,
		Boolean hasOrganizationProjects,
		Boolean hasRepositoryProjects,
		String defaultRepositoryPermission,
		Boolean membersCanCreateRepositories,
		Boolean membersCanCreatePublicRepositories,
		Boolean membersCanCreatePrivateRepositories,
		Boolean membersCanCreateInternalRepositories,
		Boolean membersCanCreatePages,
		Boolean membersCanCreatePublicPages,
		Boolean membersCanCreatePrivatePages,
		Boolean membersCanForkPrivateRepositories,
		Boolean webCommitSignoffRequired,
		Boolean deployKeysEnabledForRepositories
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String name;
		private String description;
		private String blog;
		private String company;
		private String email;
		private String location;
		private String twitterUsername;
		private Boolean hasOrganizationProjects;
		private Boolean hasRepositoryProjects;
		private String defaultRepositoryPermission;
		private Boolean membersCanCreateRepositories;
		private Boolean membersCanCreatePublicRepositories;
		private Boolean membersCanCreatePrivateRepositories;
		private Boolean membersCanCreateInternalRepositories;
		private Boolean membersCanCreatePages;
		private Boolean membersCanCreatePublicPages;
		private Boolean membersCanCreatePrivatePages;
		private Boolean membersCanForkPrivateRepositories;
		private Boolean webCommitSignoffRequired;
		private Boolean deployKeysEnabledForRepositories;

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

		public Builder blog(String blog) {
			this.blog = blog;
			return this;
		}

		public Builder company(String company) {
			this.company = company;
			return this;
		}

		public Builder email(String email) {
			this.email = email;
			return this;
		}

		public Builder location(String location) {
			this.location = location;
			return this;
		}

		public Builder twitterUsername(String twitterUsername) {
			this.twitterUsername = twitterUsername;
			return this;
		}

		public Builder hasOrganizationProjects(
				boolean hasOrganizationProjects
		) {
			this.hasOrganizationProjects = hasOrganizationProjects;
			return this;
		}

		public Builder hasRepositoryProjects(boolean hasRepositoryProjects) {
			this.hasRepositoryProjects = hasRepositoryProjects;
			return this;
		}

		public Builder defaultRepositoryPermission(
				String defaultRepositoryPermission
		) {
			this.defaultRepositoryPermission = defaultRepositoryPermission;
			return this;
		}

		public Builder membersCanCreateRepositories(
				boolean membersCanCreateRepositories
		) {
			this.membersCanCreateRepositories = membersCanCreateRepositories;
			return this;
		}

		public Builder membersCanCreatePublicRepositories(
				boolean membersCanCreatePublicRepositories
		) {
			this.membersCanCreatePublicRepositories = membersCanCreatePublicRepositories;
			return this;
		}

		public Builder membersCanCreatePrivateRepositories(
				boolean membersCanCreatePrivateRepositories
		) {
			this.membersCanCreatePrivateRepositories = membersCanCreatePrivateRepositories;
			return this;
		}

		public Builder membersCanCreateInternalRepositories(
				boolean membersCanCreateInternalRepositories
		) {
			this.membersCanCreateInternalRepositories = membersCanCreateInternalRepositories;
			return this;
		}

		public Builder membersCanCreatePages(boolean membersCanCreatePages) {
			this.membersCanCreatePages = membersCanCreatePages;
			return this;
		}

		public Builder membersCanCreatePublicPages(
				boolean membersCanCreatePublicPages
		) {
			this.membersCanCreatePublicPages = membersCanCreatePublicPages;
			return this;
		}

		public Builder membersCanCreatePrivatePages(
				boolean membersCanCreatePrivatePages
		) {
			this.membersCanCreatePrivatePages = membersCanCreatePrivatePages;
			return this;
		}

		public Builder membersCanForkPrivateRepositories(
				boolean membersCanForkPrivateRepositories
		) {
			this.membersCanForkPrivateRepositories = membersCanForkPrivateRepositories;
			return this;
		}

		public Builder webCommitSignoffRequired(
				boolean webCommitSignoffRequired
		) {
			this.webCommitSignoffRequired = webCommitSignoffRequired;
			return this;
		}

		public Builder deployKeysEnabledForRepositories(
				boolean deployKeysEnabledForRepositories
		) {
			this.deployKeysEnabledForRepositories = deployKeysEnabledForRepositories;
			return this;
		}

		public OrganizationUpdateRequest build() {
			return new OrganizationUpdateRequest(
					name,
					description,
					blog,
					company,
					email,
					location,
					twitterUsername,
					hasOrganizationProjects,
					hasRepositoryProjects,
					defaultRepositoryPermission,
					membersCanCreateRepositories,
					membersCanCreatePublicRepositories,
					membersCanCreatePrivateRepositories,
					membersCanCreateInternalRepositories,
					membersCanCreatePages,
					membersCanCreatePublicPages,
					membersCanCreatePrivatePages,
					membersCanForkPrivateRepositories,
					webCommitSignoffRequired,
					deployKeysEnabledForRepositories
			);
		}

	}

}
