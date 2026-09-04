package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualSelectedActions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

class OrgActionsPermissionsDriftGroupTest {

	@Test
	void detectsAllowedActionsDrift() {
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withAllowedActions(Drifty.AllowedActions.LOCAL_ONLY),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.ALL,
						false,
						null
				),
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly("org_actions_permissions.allowed_actions");
	}

	@Test
	void detectsPatternDriftWhenSelected() {
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withAllowedActions(Drifty.AllowedActions.SELECTED)
						.withSelectedActions(
								Desired.selectedActions()
										.withPatternsAllowed(
												List.of("my-org/*")
										)
						),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.SELECTED,
						false,
						new ActualSelectedActions(true, false, List.of())
				),
				null,
				"my-org"
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_actions_permissions.selected_actions.patterns_allowed"
				);
	}

}
