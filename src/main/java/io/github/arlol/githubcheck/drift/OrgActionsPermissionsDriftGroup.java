package io.github.arlol.githubcheck.drift;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrgActionsPermissionsRequest;
import io.github.arlol.githubcheck.client.SelectedActions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The organization's Actions policy: which repositories may run Actions, what
 * they are allowed to run, and — under {@code allowedActions = "selected"} —
 * the allow-list of actions.
 * <p>
 * Three endpoints, three {@link DriftFix} values. The policy fields
 * ({@code enabled_repositories}, {@code allowed_actions},
 * {@code sha_pinning_required}) go to one PUT, the allow-list to another and
 * the repository selection under {@code enabledRepositories = "selected"} to a
 * third; keeping them separate means a rejected policy write is not reported as
 * having failed the patterns or the selection too, and vice versa.
 */
public class OrgActionsPermissionsDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	private final Drifty.ActionsPermissions desired;
	private final @Nullable ActualOrgActionsPermissions actual;
	private final Map<String, Long> repositoryIds;
	private final GitHubClient client;
	private final String org;

	public OrgActionsPermissionsDriftGroup(
			Drifty.ActionsPermissions desired,
			@Nullable ActualOrgActionsPermissions actual,
			GitHubClient client,
			String org
	) {
		this(desired, actual, Map.of(), client, org);
	}

	/**
	 * @param repositoryIds the organization's repositories by name, which the
	 *                      repository selection is written as
	 */
	public OrgActionsPermissionsDriftGroup(
			Drifty.ActionsPermissions desired,
			@Nullable ActualOrgActionsPermissions actual,
			Map<String, Long> repositoryIds,
			GitHubClient client,
			String org
	) {
		this.desired = desired;
		this.actual = actual;
		this.repositoryIds = Map.copyOf(repositoryIds);
		this.client = client;
		this.org = org;
	}

	/**
	 * The section this group compares. Null only for a group the config does
	 * not manage, whose read was therefore never sent — and an unmanaged group
	 * is filtered out before {@code detectDrift} runs, in
	 * {@code OrganizationChecker.createDriftGroups}. Saying so here turns a
	 * would-be NullPointerException into a named invariant.
	 */
	private ActualOrgActionsPermissions present() {
		return Objects.requireNonNull(actual);
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
						present().enabledRepositories()
				),
				compare(
						"allowed_actions",
						PklTypes.allowedActions(desired.allowedActions),
						present().allowedActions()
				),
				compare(
						"sha_pinning_required",
						desired.shaPinningRequired,
						present().shaPinningRequired()
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
		// The selection only exists under "selected"; comparing it otherwise
		// would report the empty list GitHub returns as drift.
		if (desired.enabledRepositories == Drifty.ActionsEnabledRepositories.SELECTED
				|| present()
						.enabledRepositories() == ActionsEnabledRepositories.SELECTED) {
			fixes.add(selectedRepositoriesFix());
		}
		return fixes;
	}

	/**
	 * A third endpoint, so a third fix. The names are resolved to ids through
	 * the listing the checker already has, and a name that is not in it fails
	 * the whole selection rather than enabling Actions in fewer repositories
	 * than the config asks for.
	 */
	private DriftFix selectedRepositoriesFix() {
		var items = compare(
				"selected_repositories",
				desired.selectedRepositories,
				present().selectedRepositories()
		);
		return new DriftFix(items, () -> {
			var ids = new ArrayList<Long>();
			for (String repository : desired.selectedRepositories) {
				Long id = repositoryIds.get(repository);
				if (id == null) {
					return new FixResult(
							items.stream()
									.map(
											item -> new FixResult.Unfixed(
													item,
													"no repository "
															+ repository
															+ " in " + org
											)
									)
									.toList()
					);
				}
				ids.add(id);
			}
			client.setOrgActionsPermissionsRepositories(org, ids);
			return FixResult.success();
		});
	}

	/**
	 * The allow-list is a second endpoint, so it is a second fix: a rejected
	 * policy write must not be reported as having failed the patterns too.
	 */
	private DriftFix selectedActionsFix() {
		var selected = desired.selectedActions;
		var current = present().selectedActions();
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
