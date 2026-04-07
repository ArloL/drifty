package io.github.arlol.githubcheck.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.arlol.githubcheck.client.EnvironmentReviewerType;
import io.github.arlol.githubcheck.client.EnvironmentUpdateRequest;

public final class EnvironmentArgs {

	private final String name;
	private final Set<String> secrets;
	private final Integer waitTimer;
	private final DeploymentBranchPolicy deploymentBranchPolicy;
	private final List<EnvironmentUpdateRequest.Reviewer> reviewers;

	private EnvironmentArgs(Builder builder) {
		this.name = builder.name;
		this.secrets = Set.copyOf(builder.secrets);
		this.waitTimer = builder.waitTimer;
		this.deploymentBranchPolicy = builder.deploymentBranchPolicy;
		this.reviewers = List.copyOf(builder.reviewers);
	}

	public String name() {
		return this.name;
	}

	public Set<String> secrets() {
		return this.secrets;
	}

	public Integer waitTimer() {
		return this.waitTimer;
	}

	public DeploymentBranchPolicy deploymentBranchPolicy() {
		return this.deploymentBranchPolicy;
	}

	public List<EnvironmentUpdateRequest.Reviewer> reviewers() {
		return this.reviewers;
	}

	public record DeploymentBranchPolicy(
			boolean protectedBranches,
			boolean customBranchPolicies
	) {
	}

	public static Builder builder(String name) {
		return new Builder(name);
	}

	public static final class Builder {

		private final String name;
		private Set<String> secrets = Set.of();
		private Integer waitTimer = null;
		private DeploymentBranchPolicy deploymentBranchPolicy = null;
		private final List<EnvironmentUpdateRequest.Reviewer> reviewers = new ArrayList<>();

		public Builder(String name) {
			this.name = name;
		}

		public Builder secrets(String... secrets) {
			this.secrets = Set.of(secrets);
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
			this.reviewers.add(new EnvironmentUpdateRequest.Reviewer(type, id));
			return this;
		}

		public EnvironmentArgs build() {
			return new EnvironmentArgs(this);
		}

	}

}
