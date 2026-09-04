package io.github.arlol.githubcheck;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.arlol.githubcheck.actual.ActualBranchProtection;
import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.RepoRef;

/**
 * Everything drifty knows about one repository on GitHub, in drifty's own
 * vocabulary.
 * <p>
 * Nothing here is a GitHub response type: {@link ActualTypes} translates each
 * response at the client boundary, the way {@link PklTypes} translates the
 * config on the desired side. That is what keeps "how GitHub serialises this"
 * out of the drift groups, so a different read path — GraphQL bulk reads, a new
 * API version — changes the translator and
 * {@code RepositoryChecker.fetchState}, not two dozen comparisons.
 * <p>
 * The five booleans after {@code securityAndAnalysis} are the security features
 * GitHub serves from their own endpoints; for an archived repository they are
 * not fetched and read {@code false}.
 * <p>
 * The {@code ref} carries the owner, which the desired state no longer does: a
 * repository is nested under the account that owns it, so the owner reaches the
 * drift groups with the actual state rather than beside it.
 * <p>
 * {@code workflowPermissions} is null when the repository does not manage the
 * {@code workflow_permissions} group: the response is never fetched, and the
 * group that would read it is not built.
 */
public record RepositoryState(
		RepoRef ref,
		ActualRepository repository,
		ActualSecurityAndAnalysis securityAndAnalysis,
		boolean vulnerabilityAlerts,
		boolean automatedSecurityFixes,
		boolean immutableReleases,
		boolean privateVulnerabilityReporting,
		boolean codeScanningDefaultSetup,
		Map<String, ActualBranchProtection> branchProtections,
		List<ActualRuleset> rulesets,
		List<ActualSecret> actionSecrets,
		Map<String, ActualEnvironment> environments,
		Map<String, List<ActualSecret>> environmentSecrets,
		ActualWorkflowPermissions workflowPermissions,
		Optional<ActualPages> pages
) {

	public RepositoryState {
		branchProtections = Map.copyOf(branchProtections);
		rulesets = List.copyOf(rulesets);
		actionSecrets = List.copyOf(actionSecrets);
		environments = Map.copyOf(environments);
		environmentSecrets = Map.copyOf(environmentSecrets);
	}

	public String name() {
		return ref.name();
	}

}
