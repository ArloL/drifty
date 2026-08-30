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

import io.github.arlol.githubcheck.client.RepositoryDetailsResponse;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
import io.github.arlol.githubcheck.testsupport.BranchProtectionArgs;
import io.github.arlol.githubcheck.testsupport.PagesArgs;
import io.github.arlol.githubcheck.testsupport.RepositoryArgs;
import io.github.arlol.githubcheck.testsupport.RulesetArgs;
import io.github.arlol.githubcheck.testsupport.ToDrifty;

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

	private static final String SUMMARY_JSON = """
			{
				"name": "repo",
				"archived": true,
				"visibility": "public"
			}
			""";

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
	void driftGroupNamesAreUnique() {
		List<String> names = driftGroups().stream()
				.map(DriftGroup::name)
				.toList();

		assertThat(names).doesNotHaveDuplicates();
	}

	@Test
	void everyDriftItemPathIsNamespacedByItsGroup() {
		var offenders = new ArrayList<String>();
		int inspected = 0;

		for (DriftGroup group : driftGroups()) {
			for (DriftFix fix : group.detect()) {
				for (DriftItem item : fix.items()) {
					inspected++;
					String path = item.path();
					if (!path.equals(group.name())
							&& !path.startsWith(group.name() + ".")) {
						offenders.add(group.name() + " emitted " + path);
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
	private static List<DriftGroup> driftGroups() {
		var checker = new OrgChecker((String) null, false);

		RepositoryArgs desired = RepositoryArgs.create("owner", "repo")
				.description("wanted")
				.defaultBranch("main")
				.topics("java")
				.actionsSecrets("PAT")
				.environment("production", e -> e.secrets("TOKEN"))
				.pages(PagesArgs.workflow())
				.rulesets(
						RulesetArgs.builder("main").noForcePushes(true).build()
				)
				.branchProtections(
						BranchProtectionArgs.builder("main")
								.enforceAdmins(true)
								.build()
				)
				.vulnerabilityAlerts(true)
				.automatedSecurityFixes(true)
				.secretScanning(true)
				.secretScanningPushProtection(true)
				.secretScanningValidityChecks(true)
				.secretScanningNonProviderPatterns(true)
				.privateVulnerabilityReporting(true)
				.codeScanningDefaultSetup(true)
				.advancedSecurity(true)
				.secretScanningAiDetection(true)
				.secretScanningDelegatedAlertDismissal(true)
				.secretScanningDelegatedBypass(true)
				.immutableReleases(true)
				.hasIssues(true)
				.hasProjects(true)
				.hasWiki(true)
				.allowMergeCommit(true)
				.allowSquashMerge(true)
				.allowRebaseMerge(true)
				.allowAutoMerge(false)
				.deleteBranchOnMerge(false)
				.defaultWorkflowPermissions(
						WorkflowPermissions.DefaultWorkflowPermissions.READ
				)
				.canApprovePullRequestReviews(false)
				.build();

		var actual = new RepositoryState(
				"repo",
				parse(SUMMARY_JSON, RepositorySummaryResponse.class),
				parse(DETAILS_JSON, RepositoryDetailsResponse.class),
				false,
				false,
				Map.of(),
				List.of(),
				Map.of(),
				parse(WORKFLOW_PERMISSIONS_JSON, WorkflowPermissions.class),
				List.of(),
				Optional.empty(),
				Map.of(),
				false,
				false,
				false
		);

		return checker.createDriftGroups(actual, ToDrifty.repository(desired));
	}

	private static <T> T parse(String json, Class<T> type) {
		try {
			return MAPPER.readValue(json, type);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
