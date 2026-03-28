package io.github.arlol.githubcheck.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class RepositoryArgs {

	private final String name;
	private final boolean archived;
	private final PagesArgs pagesArgs;
	private final String description;
	private final String homepageUrl;
	private final String visibility;
	private final List<String> topics;
	private final List<String> requiredStatusChecks;
	private final List<String> actionsSecrets;
	private final Map<String, EnvironmentArgs> environments;
	private final List<RulesetArgs> rulesets;

	private RepositoryArgs(Builder builder) {
		this.name = builder.name;
		this.archived = builder.archived;
		this.pagesArgs = builder.pagesArgs;
		this.description = builder.description;
		this.homepageUrl = builder.homepageUrl;
		this.visibility = builder.visibility;
		this.topics = List.copyOf(builder.topics);
		this.requiredStatusChecks = List.copyOf(builder.requiredStatusChecks);
		this.actionsSecrets = List.copyOf(builder.actionsSecrets);
		this.environments = Collections
				.unmodifiableMap(new LinkedHashMap<>(builder.environments));
		this.rulesets = List.copyOf(builder.rulesets);
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

	public String visibility() {
		return visibility;
	}

	public List<String> topics() {
		return topics;
	}

	/**
	 * Extra status checks beyond the four applied to all public repos.
	 */
	public List<String> requiredStatusChecks() {
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

	public Builder toBuilder() {
		return new Builder(this);
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
		private String visibility = "public";
		private List<String> topics = List.of();
		private List<String> requiredStatusChecks = List.of();
		private List<String> actionsSecrets = List.of();
		private final Map<String, EnvironmentArgs> environments = new LinkedHashMap<>();
		private List<RulesetArgs> rulesets = List.of();

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

		public Builder visibility(String visibility) {
			this.visibility = visibility;
			return this;
		}

		public Builder topics(String... topics) {
			this.topics = List.of(topics);
			return this;
		}

		public Builder requiredStatusChecks(String... checks) {
			this.requiredStatusChecks = List.of(checks);
			return this;
		}

		public Builder addRequiredStatusChecks(String... checks) {
			List<String> combined = new ArrayList<>(this.requiredStatusChecks);
			combined.addAll(List.of(checks));
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

		public RepositoryArgs build() {
			return new RepositoryArgs(this);
		}

	}

}
