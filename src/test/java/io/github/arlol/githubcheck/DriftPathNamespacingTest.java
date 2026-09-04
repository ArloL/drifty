package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import io.github.arlol.githubcheck.actual.ActualOrganization;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * Drift paths are the identity of a drifted setting. Two groups that emit the
 * same path are indistinguishable in the report, and fix accounting cannot tell
 * them apart. Rather than assert uniqueness over one sample of drifted data,
 * these tests pin the two structural invariants that guarantee it for every
 * input: group names are unique, and each group namespaces its paths under its
 * own name.
 */
class DriftPathNamespacingTest {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.configure(
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					false
			);

	private static final String DETAILS_JSON = """
			{
				"name": "repo",
				"archived": true,
				"visibility": "public",
				"default_branch": "master",
				"description": "stale",
				"has_issues": false,
				"has_projects": false,
				"has_wiki": false,
				"allow_merge_commit": false,
				"allow_squash_merge": false,
				"allow_rebase_merge": false,
				"allow_auto_merge": true,
				"delete_branch_on_merge": true,
				"topics": ["stale-topic"]
			}
			""";

	private static final String WORKFLOW_PERMISSIONS_JSON = """
			{
				"default_workflow_permissions": "write",
				"can_approve_pull_request_reviews": true
			}
			""";

	@Test
	void everyGroupNameConstantHasAGroup() {
		List<Drifty.GroupName> names = driftGroups().stream()
				.map(DriftGroup::name)
				.toList();

		assertThat(names).doesNotHaveDuplicates()
				.containsExactlyInAnyOrder(Drifty.GroupName.values());
	}

	@Test
	void everyDriftItemPathIsNamespacedByItsGroup() {
		var offenders = new ArrayList<String>();
		int inspected = 0;

		for (DriftGroup<Drifty.GroupName> group : driftGroups()) {
			for (DriftFix fix : group.detect()) {
				for (DriftItem item : fix.items()) {
					inspected++;
					String path = item.path();
					String name = group.name().toString();
					if (!path.equals(name) && !path.startsWith(name + ".")) {
						offenders.add(name + " emitted " + path);
					}
				}
			}
		}

		assertThat(inspected)
				.as("the fixture must actually drift a broad set of groups")
				.isGreaterThan(20);
		assertThat(offenders).isEmpty();
	}

	@Test
	void everyOrgGroupNameConstantHasAGroup() {
		List<Drifty.OrgGroupName> names = orgDriftGroups().stream()
				.map(DriftGroup::name)
				.toList();

		// Only org_settings has a group so far. Task 9 lands the last of the
		// other three, and tightens this to OrgGroupName.values().
		assertThat(names).doesNotHaveDuplicates()
				.containsExactlyInAnyOrder(Drifty.OrgGroupName.ORG_SETTINGS);
	}

	@Test
	void everyOrgDriftItemPathIsNamespacedByItsGroup() {
		var offenders = new ArrayList<String>();
		int inspected = 0;

		for (DriftGroup<Drifty.OrgGroupName> group : orgDriftGroups()) {
			for (DriftFix fix : group.detect()) {
				for (DriftItem item : fix.items()) {
					inspected++;
					String path = item.path();
					String name = group.name().toString();
					if (!path.equals(name) && !path.startsWith(name + ".")) {
						offenders.add(name + " emitted " + path);
					}
				}
			}
		}

		assertThat(inspected)
				.as("the fixture must actually drift a broad set of settings")
				.isGreaterThan(20);
		assertThat(offenders).isEmpty();
	}

	@Test
	void driftedPathsAreUniqueAcrossGroups() {
		List<String> paths = driftGroups().stream()
				.flatMap(g -> g.detect().stream())
				.flatMap(f -> f.items().stream())
				.map(DriftItem::path)
				.toList();

		assertThat(paths).doesNotHaveDuplicates();
	}

	/**
	 * Groups built against a repository that drifts on as many settings as
	 * possible: every boolean flag is off on GitHub and wanted on, and the
	 * keyed sections (rulesets, branch protections, environments, secrets,
	 * pages) are configured but absent.
	 */
	private static List<DriftGroup<Drifty.GroupName>> driftGroups() {
		var checker = new RepositoryChecker((String) null, false);

		Drifty.Repository desired = Desired.repository("repo")
				.withDescription("wanted")
				.withDefaultBranch("main")
				.withTopics(List.of("java"))
				.withActionsSecrets(List.of("PAT"))
				.withEnvironments(
						Map.of(
								"production",
								Desired.environment()
										.withSecrets(List.of("TOKEN"))
						)
				)
				.withPages(Desired.pages())
				.withRulesets(
						Map.of(
								"main",
								Desired.ruleset().withNoForcePushes(true)
						)
				)
				.withBranchProtections(
						Map.of(
								"main",
								Desired.branchProtection()
										.withEnforceAdmins(true)
						)
				)
				.withVulnerabilityAlerts(true)
				.withAutomatedSecurityFixes(true)
				.withSecretScanning(true)
				.withSecretScanningPushProtection(true)
				.withSecretScanningValidityChecks(true)
				.withSecretScanningNonProviderPatterns(true)
				.withPrivateVulnerabilityReporting(true)
				.withCodeScanningDefaultSetup(true)
				.withAdvancedSecurity(true)
				.withSecretScanningAiDetection(true)
				.withSecretScanningDelegatedAlertDismissal(true)
				.withSecretScanningDelegatedBypass(true)
				.withImmutableReleases(true)
				.withHasIssues(true)
				.withHasProjects(true)
				.withHasWiki(true)
				.withAllowMergeCommit(true)
				.withAllowSquashMerge(true)
				.withAllowRebaseMerge(true)
				.withAllowAutoMerge(false)
				.withDeleteBranchOnMerge(false)
				.withDefaultWorkflowPermissions(Drifty.WorkflowPermissions.READ)
				.withCanApprovePullRequestReviews(false);

		var details = parse(DETAILS_JSON, RepositoryDetailsResponse.class);
		var actual = new RepositoryState(
				new RepoRef("owner", "repo"),
				ActualTypes.repository(details),
				ActualTypes.securityAndAnalysis(details),
				false,
				false,
				false,
				false,
				false,
				Map.of(),
				List.of(),
				List.of(),
				Map.of(),
				Map.of(),
				ActualTypes.workflowPermissions(
						parse(
								WORKFLOW_PERMISSIONS_JSON,
								WorkflowPermissions.class
						)
				),
				Optional.empty()
		);

		return checker.createDriftGroups(actual, desired);
	}

	/**
	 * Groups built against an organization whose every managed setting differs
	 * from the config: each string is another string, each flag is inverted.
	 */
	private static List<DriftGroup<Drifty.OrgGroupName>> orgDriftGroups() {
		var checker = new OrganizationChecker(
				null,
				false,
				Map.of(),
				new DriftyState()
		);

		var actual = new ActualOrganization(
				"stale",
				"stale",
				"stale",
				"stale",
				"stale",
				"stale",
				"stale",
				false,
				false,
				"admin",
				false,
				false,
				false,
				true,
				false,
				false,
				false,
				true,
				true,
				true,
				"master",
				true,
				false,
				false,
				false,
				true,
				false,
				false,
				true,
				true
		);

		return checker.createDriftGroups(
				new OrganizationState("my-org", actual, null, null, List.of()),
				Desired.organization(),
				Map.of()
		);
	}

	private static <T> T parse(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
