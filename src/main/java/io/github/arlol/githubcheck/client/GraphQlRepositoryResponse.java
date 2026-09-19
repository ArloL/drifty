package io.github.arlol.githubcheck.client;

import java.util.List;
import java.util.Map;

/**
 * What one GraphQL query answers about a repository, in the shapes the REST
 * reads it replaces returned.
 * <p>
 * Each of the four is asked for as its own aliased {@code repository}
 * selection, because GitHub nulls the whole {@code repository} object when a
 * field inside it is forbidden — one alias each is what keeps a token that may
 * not read branch protection from losing the rulesets too. A section GitHub
 * refused throws when it is read, so the group asking for it records the
 * failure and the other three do not, which is the granularity four separate
 * requests had.
 */
public final class GraphQlRepositoryResponse {

	private final Section<List<RulesetDetailsResponse>> rulesets;
	private final Section<Map<String, BranchProtectionResponse>> branchProtections;
	private final Section<List<CollaboratorResponse>> collaborators;
	private final Section<Boolean> vulnerabilityAlerts;

	GraphQlRepositoryResponse(
			Section<List<RulesetDetailsResponse>> rulesets,
			Section<Map<String, BranchProtectionResponse>> branchProtections,
			Section<List<CollaboratorResponse>> collaborators,
			Section<Boolean> vulnerabilityAlerts
	) {
		this.rulesets = rulesets;
		this.branchProtections = branchProtections;
		this.collaborators = collaborators;
		this.vulnerabilityAlerts = vulnerabilityAlerts;
	}

	public List<RulesetDetailsResponse> rulesets() {
		return rulesets.get();
	}

	/**
	 * The protections by branch name, not by the pattern GraphQL keys them on.
	 */
	public Map<String, BranchProtectionResponse> branchProtections() {
		return branchProtections.get();
	}

	public List<CollaboratorResponse> collaborators() {
		return collaborators.get();
	}

	public boolean vulnerabilityAlerts() {
		return vulnerabilityAlerts.get();
	}

	/** One aliased selection: what it answered, or why it did not. */
	record Section<T>(
			T value,
			String error
	) {

		static <T> Section<T> of(T value) {
			return new Section<>(value, null);
		}

		static <T> Section<T> failed(String error) {
			return new Section<>(null, error);
		}

		T get() {
			if (error != null) {
				throw new GitHubApiException(error);
			}
			return value;
		}

	}

}
