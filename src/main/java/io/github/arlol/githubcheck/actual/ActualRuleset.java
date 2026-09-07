package io.github.arlol.githubcheck.actual;

import java.util.List;
import java.util.Set;

/**
 * A ruleset as it exists on GitHub, flattened to the settings drifty compares.
 * Repository and organization rulesets share the shape; the repository
 * conditions at the end are empty on a repository ruleset, which has none.
 * <p>
 * GitHub returns rulesets as a list of typed rule objects, each with its own
 * nested parameters. Unpicking that shape is the client's job, not the drift
 * comparison's — see {@code ActualTypes}. This record is the vocabulary the
 * comparison works in, so a change to the read path stops at the translator.
 */
public record ActualRuleset(
		long id,
		String name,
		String target,
		String enforcement,
		Set<String> includePatterns,
		Set<String> excludePatterns,
		boolean creation,
		boolean deletion,
		boolean update,
		boolean updateAllowsFetchAndMerge,
		boolean requiredSignatures,
		boolean requiredLinearHistory,
		boolean noForcePushes,
		boolean strictRequiredStatusChecks,
		Set<StatusCheck> requiredStatusChecks,
		PullRequest pullRequest,
		Set<String> requiredCodeScanningTools,
		Set<String> requiredDeployments,
		RulePattern commitMessagePattern,
		RulePattern commitAuthorEmailPattern,
		RulePattern committerEmailPattern,
		RulePattern branchNamePattern,
		RulePattern tagNamePattern,
		MergeQueue mergeQueue,
		Set<Workflow> workflows,
		Set<String> filePathRestrictions,
		Integer maxFilePathLength,
		Set<String> fileExtensionRestrictions,
		Integer maxFileSize,
		List<BypassActor> bypassActors,
		Set<String> repositoryNameInclude,
		Set<String> repositoryNameExclude,
		boolean repositoryNameProtected,
		Set<PropertyCondition> repositoryPropertyInclude,
		Set<PropertyCondition> repositoryPropertyExclude
) {

	/**
	 * A commit message, author email, committer email, branch name or tag name
	 * pattern rule's parameters; null when the rule is absent, the same way
	 * every other rule here reads. {@code operator} is the wire spelling
	 * ({@code starts_with}, {@code regex}, ...), matching how every other
	 * enum-shaped field on this record is compared and exported as a plain
	 * string. A reviewer without a {@code negate} defaults to {@code false},
	 * the schema's own default for the field.
	 */
	public record RulePattern(
			String name,
			boolean negate,
			String operator,
			String pattern
	) {
	}

	/** The pull_request rule's parameters; null when the rule is absent. */
	public record PullRequest(
			int requiredApprovingReviewCount,
			boolean dismissStaleReviewsOnPush,
			boolean requireCodeOwnerReview,
			boolean requireLastPushApproval,
			boolean requiredReviewThreadResolution,
			Set<String> allowedMergeMethods
	) {

		public PullRequest {
			allowedMergeMethods = Set.copyOf(allowedMergeMethods);
		}

	}

	/** The merge_queue rule's parameters; null when the rule is absent. */
	public record MergeQueue(
			int checkResponseTimeoutMinutes,
			String groupingStrategy,
			int maxEntriesToBuild,
			int maxEntriesToMerge,
			String mergeMethod,
			int minEntriesToMerge,
			int minEntriesToMergeWaitMinutes
	) {
	}

	/**
	 * One required workflow. Rendered as {@code <path>@<ref>#<repositoryId>}
	 * when compared, with the ref part left out when none is set.
	 */
	public record Workflow(
			String path,
			long repositoryId,
			String ref
	) {

		@Override
		public String toString() {
			return path + (ref == null ? "" : "@" + ref) + "#" + repositoryId;
		}

	}

	/**
	 * A repository property an organization ruleset selects by. Rendered as
	 * {@code <source>:<name>=<values>} when compared.
	 */
	public record PropertyCondition(
			String name,
			Set<String> propertyValues,
			String source
	) {

		public PropertyCondition {
			propertyValues = Set.copyOf(propertyValues);
		}

		@Override
		public String toString() {
			return source + ":" + name + "="
					+ propertyValues.stream().sorted().toList();
		}

	}

	/**
	 * An actor allowed to bypass the ruleset. Rendered as a single string when
	 * compared, so the comparison does not depend on GitHub's field names.
	 *
	 * @param actorType  role, team, integration or organisation admin
	 * @param actorId    the actor's numeric id
	 * @param bypassMode always, or pull requests only
	 */
	public record BypassActor(
			String actorType,
			Long actorId,
			String bypassMode
	) {

		@Override
		public String toString() {
			return actorType + ":" + actorId + ":" + bypassMode;
		}

	}

	public ActualRuleset {
		includePatterns = Set.copyOf(includePatterns);
		excludePatterns = Set.copyOf(excludePatterns);
		requiredStatusChecks = Set.copyOf(requiredStatusChecks);
		requiredCodeScanningTools = Set.copyOf(requiredCodeScanningTools);
		requiredDeployments = Set.copyOf(requiredDeployments);
		workflows = Set.copyOf(workflows);
		filePathRestrictions = Set.copyOf(filePathRestrictions);
		fileExtensionRestrictions = Set.copyOf(fileExtensionRestrictions);
		bypassActors = List.copyOf(bypassActors);
		repositoryNameInclude = Set.copyOf(repositoryNameInclude);
		repositoryNameExclude = Set.copyOf(repositoryNameExclude);
		repositoryPropertyInclude = Set.copyOf(repositoryPropertyInclude);
		repositoryPropertyExclude = Set.copyOf(repositoryPropertyExclude);
	}

}
