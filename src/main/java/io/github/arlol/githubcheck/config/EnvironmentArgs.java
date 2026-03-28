package io.github.arlol.githubcheck.config;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.client.EnvironmentReviewerType;

public final class EnvironmentArgs {

	private final String name;
	private final List<String> secrets;
	private final Integer waitTimer;
	private final DeploymentBranchPolicy deploymentBranchPolicy;
	private final List<Reviewer> reviewers;

	private EnvironmentArgs(Builder builder) {
		this.name = builder.name;
		this.secrets = List.copyOf(builder.secrets);
		this.waitTimer = builder.waitTimer;
		this.deploymentBranchPolicy = builder.deploymentBranchPolicy;
		this.reviewers = List.copyOf(builder.reviewers);
	}

	public String name() {
		return this.name;
	}

	public List<String> secrets() {
		return this.secrets;
	}

	public Integer waitTimer() {
		return this.waitTimer;
	}

	public DeploymentBranchPolicy deploymentBranchPolicy() {
		return this.deploymentBranchPolicy;
	}

	public List<Reviewer> reviewers() {
		return this.reviewers;
	}

	public record DeploymentBranchPolicy(
			boolean protectedBranches,
			boolean customBranchPolicies
	) {
	}

	public record Reviewer(
			EnvironmentReviewerType type,
			long id
	) {
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static final class Builder {

		private final String name;
		private List<String> secrets = List.of();
		private Integer waitTimer = null;
		private DeploymentBranchPolicy deploymentBranchPolicy = null;
		private List<Reviewer> reviewers = new ArrayList<>();

		public Builder(String name) {
			this.name = name;
		}

		public Builder secrets(String... secrets) {
			this.secrets = List.of(secrets);
			return this;
		}

		public Builder waitTimer(int minutes) {
			this.waitTimer = minutes;
			return this;
		}

		public Builder deploymentBranchPolicy(
				boolean protectedBranches,
				boolean customBranchPolicies
		) {
			this.deploymentBranchPolicy = new DeploymentBranchPolicy(
					protectedBranches,
					customBranchPolicies
			);
			return this;
		}

		public Builder reviewer(EnvironmentReviewerType type, long id) {
			this.reviewers.add(new Reviewer(type, id));
			return this;
		}

		public EnvironmentArgs build() {
			return new EnvironmentArgs(this);
		}

	}

}
