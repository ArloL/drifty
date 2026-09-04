package io.github.arlol.githubcheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.GitHubApiException;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepositorySummaryResponse;
import io.github.arlol.githubcheck.drift.DriftFix;
import io.github.arlol.githubcheck.drift.DriftFixer;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.drift.DriftItem;
import io.github.arlol.githubcheck.drift.ManagedGroups;
import io.github.arlol.githubcheck.drift.OrgActionsPermissionsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgSettingsDriftGroup;
import io.github.arlol.githubcheck.drift.OrgWorkflowPermissionsDriftGroup;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Checks one organization: fetches its actual state, compares it against
 * {@code drifty.pkl}, and — in fix mode — writes the drift away. The org-level
 * counterpart of {@link RepositoryChecker}.
 */
public class OrganizationChecker {

	private final GitHubClient client;
	private final boolean fix;
	// Both are the org secrets group's, which arrives with that group: a secret
	// value comes from DRIFTY_GITHUB_SECRETS, and the state file is what says
	// which value drifty last wrote, since GitHub never reads one back.
	private final Map<String, String> githubSecrets;
	private final DriftyState state;

	public OrganizationChecker(
			GitHubClient client,
			boolean fix,
			Map<String, String> githubSecrets,
			DriftyState state
	) {
		this.client = client;
		this.fix = fix;
		this.githubSecrets = githubSecrets;
		this.state = state;
	}

	/**
	 * Checks one organization against the block the config keys under its
	 * login.
	 *
	 * @param repos the organization's repositories as GitHub listed them. The
	 *              caller already has that listing, and the org secrets group
	 *              needs it to turn a secret's selected repositories into names
	 */
	public CheckResult.Entry check(
			String login,
			Drifty.Organization desired,
			List<RepositorySummaryResponse> repos
	) {
		ManagedGroups<Drifty.OrgGroupName> managed = ManagedGroups
				.of(desired.managed);
		List<String> unmanaged = managed.unmanaged()
				.stream()
				.map(Drifty.OrgGroupName::toString)
				.toList();
		try {
			OrganizationState actual = fetchState(login, managed);
			if (actual.settings() == null) {
				return CheckResult.Entry.missing(login);
			}
			var groupDrifts = computeGroupDrifts(
					actual,
					desired,
					repositoryIds(repos)
			);

			if (fix) {
				var outcome = DriftFixer.applyFixes(groupDrifts);
				return CheckResult.Entry.fixed(
						login,
						DriftFixer.render(outcome.unfixedItems()),
						DriftFixer.fixReports(outcome)
				);
			}

			List<String> diffs = groupDrifts.values()
					.stream()
					.flatMap(List::stream)
					.flatMap(driftFix -> driftFix.items().stream())
					.map(DriftItem::message)
					.toList();
			if (diffs.isEmpty()) {
				return CheckResult.Entry.ok(login, unmanaged);
			}
			// In check mode, preview which groups --fix would act on.
			return CheckResult.Entry.drift(
					login,
					diffs,
					groupDrifts.keySet()
							.stream()
							.map(group -> group.name().toString())
							.toList(),
					unmanaged
			);
		} catch (GitHubApiException e) {
			return CheckResult.Entry.error(login, e.getMessage());
		}
	}

	private static Map<String, Long> repositoryIds(
			List<RepositorySummaryResponse> repos
	) {
		return repos.stream()
				.filter(repo -> repo.id() != null)
				.collect(
						Collectors.toMap(
								RepositorySummaryResponse::name,
								RepositorySummaryResponse::id,
								(a, _) -> a
						)
				);
	}

	// ─── Fetch
	// ──────────────────────────────────────────────────────────────

	/**
	 * Reads the organization state, one request per managed group.
	 * <p>
	 * {@code GET /orgs/{org}} is sent even when {@code org_settings} is
	 * unmanaged: it is how drifty learns the organization exists, and any
	 * member can read it. Every other request here belongs to a group and is
	 * skipped with it — filtering a group out of the comparison alone would
	 * still send its request, and an organization someone else administers is
	 * where those return 403.
	 * <p>
	 * When that first read 404s there is nothing to read the rest of: sending
	 * the group requests anyway would turn "this organization does not exist"
	 * into whichever error the next endpoint answered with, so the state comes
	 * back with a null {@code settings} and {@link #check} reports it missing.
	 */
	OrganizationState fetchState(
			String login,
			ManagedGroups<Drifty.OrgGroupName> managed
	) {
		var organization = client.getOrganization(login);
		if (organization.isEmpty()) {
			return new OrganizationState(login, null, null, null, List.of());
		}
		var settings = ActualTypes.organization(organization.orElseThrow());

		ActualOrgActionsPermissions permissions = null;
		if (managed.manages(Drifty.OrgGroupName.ORG_ACTIONS_PERMISSIONS)) {
			var response = client.getOrgActionsPermissions(login);
			// The allow-list only exists in "selected" mode; asking for it in
			// any other mode is a 404.
			var selected = response.allowedActions() == AllowedActions.SELECTED
					? client.getOrgSelectedActions(login)
					: null;
			permissions = ActualTypes.orgActionsPermissions(response, selected);
		}

		var workflowPermissions = managed
				.manages(Drifty.OrgGroupName.ORG_WORKFLOW_PERMISSIONS)
						? ActualTypes.workflowPermissions(
								client.getOrgWorkflowPermissions(login)
						)
						: null;

		List<ActualOrgSecret> secrets = managed
				.manages(Drifty.OrgGroupName.ORG_ACTION_SECRETS)
						? orgSecrets(login)
						: List.of();

		return new OrganizationState(
				login,
				settings,
				permissions,
				workflowPermissions,
				secrets
		);
	}

	/** Reading the org secrets arrives with the group that compares them. */
	private List<ActualOrgSecret> orgSecrets(String login) {
		return List.of();
	}

	// ─── Drift groups
	// ──────────────────────────────────────────────────────────────

	Map<DriftGroup<Drifty.OrgGroupName>, List<DriftFix>> computeGroupDrifts(
			OrganizationState actual,
			Drifty.Organization desired,
			Map<String, Long> repositoryIds
	) {
		Map<DriftGroup<Drifty.OrgGroupName>, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
		for (var group : createDriftGroups(actual, desired, repositoryIds)) {
			var fixes = group.detect();
			if (!fixes.isEmpty()) {
				groupDrifts.put(group, fixes);
			}
		}
		return groupDrifts;
	}

	List<DriftGroup<Drifty.OrgGroupName>> createDriftGroups(
			OrganizationState actual,
			Drifty.Organization desired,
			Map<String, Long> repositoryIds
	) {
		var groups = new ArrayList<DriftGroup<Drifty.OrgGroupName>>();
		groups.add(
				new OrgSettingsDriftGroup(
						desired,
						actual.settings(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgActionsPermissionsDriftGroup(
						desired.actionsPermissions,
						actual.actionsPermissions(),
						client,
						actual.login()
				)
		);
		groups.add(
				new OrgWorkflowPermissionsDriftGroup(
						desired.defaultWorkflowPermissions,
						desired.canApprovePullRequestReviews,
						actual.workflowPermissions(),
						client,
						actual.login()
				)
		);
		ManagedGroups<Drifty.OrgGroupName> managed = ManagedGroups
				.of(desired.managed);
		// One filter over the finished list, so a group added later is filtered
		// without its author having to know this feature exists.
		return groups.stream()
				.filter(group -> managed.manages(group.name()))
				.toList();
	}

}
