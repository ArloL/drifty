package io.github.arlol.githubcheck.config;

import io.github.arlol.githubcheck.client.RepositoryVisibility;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class RepositoryArgs {

	private final String name;
	private final boolean archived;
	private final PagesArgs pagesArgs;
	private final String description;
	private final String homepageUrl;
	private final RepositoryVisibility visibility;
	private final List<String> topics;
	private final Set<StatusCheckArgs> requiredStatusChecks;
	private final List<String> actionsSecrets;
	private final Map<String, EnvironmentArgs> environments;
	private final List<RulesetArgs> rulesets;
	private final boolean immutableReleases;
	private final boolean allowRebaseMerge;

	private RepositoryArgs(Builder builder) {
		this.name = builder.name;
		this.archived = builder.archived;
		this.pagesArgs = builder.pagesArgs;
		this.description = builder.description;
		this.homepageUrl = builder.homepageUrl;
		this.visibility = builder.visibility;
		this.topics = List.copyOf(builder.topics);
		this.requiredStatusChecks = Set.copyOf(builder.requiredStatusChecks);
		this.actionsSecrets = List.copyOf(builder.actionsSecrets);
		this.environments = Collections
				.unmodifiableMap(new LinkedHashMap<>(builder.environments));
		this.rulesets = List.copyOf(builder.rulesets);
		this.immutableReleases = builder.immutableReleases;
		this.allowRebaseMerge = builder.allowRebaseMerge;
	}

	public String name() {
		return name;
	}

	public boolean archived() {
		return archived;
	}

	public boolean pages() {
		return pagesArgs != null;
	}

	public PagesArgs pagesArgs() {
		return pagesArgs;
	}

	public String description() {
		return description;
	}

	public String homepageUrl() {
		return homepageUrl;
	}

	public RepositoryVisibility visibility() {
		return visibility;
	}

	public List<String> topics() {
		return topics;
	}

	public Set<StatusCheckArgs> requiredStatusChecks() {
		return requiredStatusChecks;
	}

	public List<String> actionsSecrets() {
		return actionsSecrets;
	}

	public Map<String, EnvironmentArgs> environments() {
		return environments;
	}

	public List<RulesetArgs> rulesets() {
		return rulesets;
	}

	public boolean immutableReleases() {
		return immutableReleases;
	}

	public boolean allowRebaseMerge() {
		return allowRebaseMerge;
	}

	public Builder toBuilder() {
		return new Builder(this);
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		RepositoryArgs that = (RepositoryArgs) o;
		return archived == that.archived
				&& immutableReleases == that.immutableReleases
				&& allowRebaseMerge == that.allowRebaseMerge
				&& Objects.equals(name, that.name)
				&& Objects.equals(pagesArgs, that.pagesArgs)
				&& Objects.equals(description, that.description)
				&& Objects.equals(homepageUrl, that.homepageUrl)
				&& Objects.equals(visibility, that.visibility)
				&& Objects.equals(topics, that.topics)
				&& Objects
						.equals(requiredStatusChecks, that.requiredStatusChecks)
				&& Objects.equals(actionsSecrets, that.actionsSecrets)
				&& Objects.equals(environments, that.environments)
				&& Objects.equals(rulesets, that.rulesets);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				name,
				archived,
				pagesArgs,
				description,
				homepageUrl,
				visibility,
				topics,
				requiredStatusChecks,
				actionsSecrets,
				environments,
				rulesets,
				immutableReleases,
				allowRebaseMerge
		);
	}

	public static Builder create(String name) {
		return new Builder(name);
	}

	public static RepositoryArgs archived(String name) {
		return new Builder(name).archived().build();
	}

	public static final class Builder {

		private String name;
		private boolean archived = false;
		private PagesArgs pagesArgs = null;
		private String description = "";
		private String homepageUrl = "";
		private RepositoryVisibility visibility = RepositoryVisibility.PUBLIC;
		private List<String> topics = List.of();
		private Set<StatusCheckArgs> requiredStatusChecks = Set.of();
		private List<String> actionsSecrets = List.of();
		private final Map<String, EnvironmentArgs> environments = new LinkedHashMap<>();
		private List<RulesetArgs> rulesets = List.of();
		private boolean immutableReleases = false;
		private boolean allowRebaseMerge = true;

		public Builder(String name) {
			this.name = name;
		}

		public Builder(RepositoryArgs repositoryArgs) {
			this.name = repositoryArgs.name;
			this.archived = repositoryArgs.archived;
			this.pagesArgs = repositoryArgs.pagesArgs;
			this.description = repositoryArgs.description;
			this.homepageUrl = repositoryArgs.homepageUrl;
			this.visibility = repositoryArgs.visibility;
			this.topics = repositoryArgs.topics;
			this.requiredStatusChecks = repositoryArgs.requiredStatusChecks;
			this.actionsSecrets = repositoryArgs.actionsSecrets;
			this.environments.putAll(repositoryArgs.environments);
			this.rulesets = repositoryArgs.rulesets;
			this.immutableReleases = repositoryArgs.immutableReleases;
			this.allowRebaseMerge = repositoryArgs.allowRebaseMerge;
		}

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder archived() {
			this.archived = true;
			return this;
		}

		public Builder pages() {
			this.pagesArgs = PagesArgs.workflow();
			return this;
		}

		public Builder pages(PagesArgs pagesArgs) {
			this.pagesArgs = pagesArgs;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder homepageUrl(String homepageUrl) {
			this.homepageUrl = homepageUrl;
			return this;
		}

		public Builder visibility(RepositoryVisibility visibility) {
			this.visibility = visibility;
			return this;
		}

		public Builder topics(String... topics) {
			this.topics = List.of(topics);
			return this;
		}

		public Builder requiredStatusChecks(
				StatusCheckArgs... statusCheckArgs
		) {
			this.requiredStatusChecks = Set.of(statusCheckArgs);
			return this;
		}

		public Builder addRequiredStatusChecks(
				StatusCheckArgs... statusCheckArgs
		) {
			Set<StatusCheckArgs> combined = new HashSet<>(
					this.requiredStatusChecks
			);
			combined.addAll(List.of(statusCheckArgs));
			this.requiredStatusChecks = combined;
			return this;
		}

		public Builder actionsSecrets(String... secrets) {
			this.actionsSecrets = List.of(secrets);
			return this;
		}

		public Builder environment(String name, EnvironmentArgs args) {
			this.environments.put(name, args);
			return this;
		}

		public Builder environment(
				String name,
				Consumer<EnvironmentArgs.Builder> configure
		) {
			var envBuilder = EnvironmentArgs.builder(name);
			configure.accept(envBuilder);
			this.environments.put(name, envBuilder.build());
			return this;
		}

		public Builder rulesets(RulesetArgs... rulesets) {
			this.rulesets = List.of(rulesets);
			return this;
		}

		public Builder immutableReleases(boolean immutableReleases) {
			this.immutableReleases = immutableReleases;
			return this;
		}

		public Builder allowRebaseMerge(boolean allowRebaseMerge) {
			this.allowRebaseMerge = allowRebaseMerge;
			return this;
		}

		public RepositoryArgs build() {
			return new RepositoryArgs(this);
		}

	}

}
