package io.github.arlol.githubcheck.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

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
@GitHubEndpoint(
		request = "PATCH /orgs/{org}",
		unmanaged = {
				"advanced_security_enabled_for_new_repositories — a default applied to repositories created later; drifty reconciles each repository's own security settings, which is the state that actually holds",
				"dependabot_alerts_enabled_for_new_repositories — a default for new repositories, as above",
				"dependabot_security_updates_enabled_for_new_repositories — a default for new repositories, as above",
				"dependency_graph_enabled_for_new_repositories — a default for new repositories, as above",
				"secret_scanning_enabled_for_new_repositories — a default for new repositories, as above",
				"secret_scanning_push_protection_enabled_for_new_repositories — a default for new repositories, as above",
				"secret_scanning_push_protection_custom_link — belongs with the push-protection default above and is meaningless without it",
				"billing_email — billing is not configuration drifty reconciles",
				"members_allowed_repository_creation_type — deprecated by GitHub in favour of the members_can_create_* booleans, which OrgSettingsDriftGroup does compare" }
)
public record OrganizationUpdateRequest(
		@Nullable String name,
		@Nullable String description,
		@Nullable String blog,
		@Nullable String company,
		@Nullable String email,
		@Nullable String location,
		@Nullable String twitterUsername,
		@Nullable Boolean hasOrganizationProjects,
		@Nullable Boolean hasRepositoryProjects,
		@Nullable String defaultRepositoryPermission,
		@Nullable Boolean membersCanCreateRepositories,
		@Nullable Boolean membersCanCreatePublicRepositories,
		@Nullable Boolean membersCanCreatePrivateRepositories,
		@Nullable Boolean membersCanCreateInternalRepositories,
		@Nullable Boolean membersCanCreatePages,
		@Nullable Boolean membersCanCreatePublicPages,
		@Nullable Boolean membersCanCreatePrivatePages,
		@Nullable Boolean membersCanForkPrivateRepositories,
		@Nullable Boolean webCommitSignoffRequired,
		@Nullable Boolean deployKeysEnabledForRepositories
) {

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String name;
		private @Nullable String description;
		private @Nullable String blog;
		private @Nullable String company;
		private @Nullable String email;
		private @Nullable String location;
		private @Nullable String twitterUsername;
		private @Nullable Boolean hasOrganizationProjects;
		private @Nullable Boolean hasRepositoryProjects;
		private @Nullable String defaultRepositoryPermission;
		private @Nullable Boolean membersCanCreateRepositories;
		private @Nullable Boolean membersCanCreatePublicRepositories;
		private @Nullable Boolean membersCanCreatePrivateRepositories;
		private @Nullable Boolean membersCanCreateInternalRepositories;
		private @Nullable Boolean membersCanCreatePages;
		private @Nullable Boolean membersCanCreatePublicPages;
		private @Nullable Boolean membersCanCreatePrivatePages;
		private @Nullable Boolean membersCanForkPrivateRepositories;
		private @Nullable Boolean webCommitSignoffRequired;
		private @Nullable Boolean deployKeysEnabledForRepositories;

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
