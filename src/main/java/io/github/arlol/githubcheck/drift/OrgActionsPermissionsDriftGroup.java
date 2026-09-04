package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrgActionsPermissionsRequest;
import io.github.arlol.githubcheck.client.SelectedActions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The organization's Actions policy: which repositories may run Actions, what
 * they are allowed to run, and — under {@code allowedActions = "selected"} —
 * the allow-list of actions.
 * <p>
 * Two endpoints, two {@link DriftFix} values. The policy fields
 * ({@code enabled_repositories}, {@code allowed_actions},
 * {@code sha_pinning_required}) go to one PUT and the allow-list to another;
 * keeping them separate means a rejected policy write is not reported as having
 * failed the patterns too, and vice versa.
 */
public class OrgActionsPermissionsDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	private final Drifty.ActionsPermissions desired;
	private final ActualOrgActionsPermissions actual;
	private final GitHubClient client;
	private final String org;

	public OrgActionsPermissionsDriftGroup(
			Drifty.ActionsPermissions desired,
			ActualOrgActionsPermissions actual,
			GitHubClient client,
			String org
	) {
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_ACTIONS_PERMISSIONS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var policyItems = combine(
				compare(
						"enabled_repositories",
						PklTypes.enabledRepositories(
								desired.enabledRepositories
						),
						actual.enabledRepositories()
				),
				compare(
						"allowed_actions",
						PklTypes.allowedActions(desired.allowedActions),
						actual.allowedActions()
				),
				compare(
						"sha_pinning_required",
						desired.shaPinningRequired,
						actual.shaPinningRequired()
				)
		);
		var fixes = new ArrayList<DriftFix>();
		fixes.add(new DriftFix(policyItems, () -> {
			client.updateOrgActionsPermissions(
					org,
					new OrgActionsPermissionsRequest(
							PklTypes.enabledRepositories(
									desired.enabledRepositories
							),
							PklTypes.allowedActions(desired.allowedActions),
							desired.shaPinningRequired
					)
			);
			return FixResult.success();
		}));
		if (desired.selectedActions != null) {
			fixes.add(selectedActionsFix());
		}
		return fixes;
	}

	/**
	 * The allow-list is a second endpoint, so it is a second fix: a rejected
	 * policy write must not be reported as having failed the patterns too.
	 */
	private DriftFix selectedActionsFix() {
		var selected = desired.selectedActions;
		var current = actual.selectedActions();
		boolean githubOwned = current != null && current.githubOwnedAllowed();
		boolean verified = current != null && current.verifiedAllowed();
		List<String> patterns = current == null ? List.of()
				: current.patternsAllowed();

		var items = combine(
				compare(
						"selected_actions.github_owned_allowed",
						selected.githubOwnedAllowed,
						githubOwned
				),
				compare(
						"selected_actions.verified_allowed",
						selected.verifiedAllowed,
						verified
				),
				compare(
						"selected_actions.patterns_allowed",
						selected.patternsAllowed,
						patterns
				)
		);
		return new DriftFix(items, () -> {
			client.updateOrgSelectedActions(
					org,
					new SelectedActions(
							selected.githubOwnedAllowed,
							selected.verifiedAllowed,
							selected.patternsAllowed
					)
			);
			return FixResult.success();
		});
	}

}
