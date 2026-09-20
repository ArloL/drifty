package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualSelectedActions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
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

	/**
	 * The selection is compared only when one side is {@code selected}, and is
	 * written as ids resolved through the repository listing.
	 */
	@Test
	void selectedRepositoriesAreComparedAndWrittenAsIds(
			WireMockRuntimeInfo wm
	) {
		var client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
		stubFor(
				put(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/repositories"
						)
				).willReturn(aResponse().withStatus(204))
		);
		var unselected = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions(),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.ALL,
						false,
						null,
						List.of("stale")
				),
				Map.of("one", 10L),
				client,
				"my-org"
		);
		assertThat(unselected.detect()).flatExtracting(DriftFix::items)
				.isEmpty();

		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withEnabledRepositories(
								Drifty.ActionsEnabledRepositories.SELECTED
						)
						.withSelectedRepositories(List.of("one")),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.SELECTED,
						AllowedActions.ALL,
						false,
						null,
						List.of()
				),
				Map.of("one", 10L),
				client,
				"my-org"
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_actions_permissions.selected_repositories"
				);
		var selection = fixes.stream()
				.filter(f -> !f.items().isEmpty())
				.findFirst()
				.orElseThrow();
		assertThat(selection.fix().execute().unfixedItems()).isEmpty();
		verify(
				putRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/repositories"
						)
				).withRequestBody(
						equalToJson("{\"selected_repository_ids\": [10]}")
				)
		);
	}

	/**
	 * Detecting the drift and writing it are separate halves, and only the
	 * first was tested: the policy fix compared three settings, built the
	 * request and nothing ran it, so dropping the write entirely broke no test.
	 */
	@Test
	void thePolicyFixWritesTheThreeSettingsItCompared(WireMockRuntimeInfo wm) {
		stubFor(
				put(urlPathEqualTo("/orgs/my-org/actions/permissions"))
						.willReturn(aResponse().withStatus(204))
		);
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withEnabledRepositories(
								Drifty.ActionsEnabledRepositories.NONE
						)
						.withAllowedActions(Drifty.AllowedActions.LOCAL_ONLY)
						.withShaPinningRequired(true),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.ALL,
						false,
						null,
						List.of()
				),
				Map.of(),
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				"my-org"
		);

		var policy = group.detect().getFirst();

		assertThat(policy.items()).extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_actions_permissions.enabled_repositories",
						"org_actions_permissions.allowed_actions",
						"org_actions_permissions.sha_pinning_required"
				);
		assertThat(policy.fix().execute().unfixedItems()).isEmpty();
		verify(
				putRequestedFor(
						urlPathEqualTo("/orgs/my-org/actions/permissions")
				).withRequestBody(equalToJson("""
						{
						  "enabled_repositories": "none",
						  "allowed_actions": "local_only",
						  "sha_pinning_required": true
						}"""))
		);
	}

	/**
	 * CLAUDE.md: "a name that is not in it fails the whole fix rather than
	 * writing a shorter list and reporting success." The shorter list is the
	 * dangerous half — it would take Actions away from every repository the
	 * config named that drifty could not resolve, and report FIXED. Nothing
	 * tested it.
	 */
	@Test
	void aRepositoryNameWithNoIdFailsTheFixInsteadOfWritingAShorterList(
			WireMockRuntimeInfo wm
	) {
		stubFor(
				put(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/repositories"
						)
				).willReturn(aResponse().withStatus(204))
		);
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withEnabledRepositories(
								Drifty.ActionsEnabledRepositories.SELECTED
						)
						.withSelectedRepositories(List.of("known", "unknown")),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.SELECTED,
						AllowedActions.ALL,
						false,
						null,
						List.of()
				),
				Map.of("known", 10L),
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				"my-org"
		);

		var selection = group.detect()
				.stream()
				.filter(f -> !f.items().isEmpty())
				.findFirst()
				.orElseThrow();
		var result = selection.fix().execute();

		assertThat(result.unfixedItems()).singleElement().satisfies(unfixed -> {
			assertThat(unfixed.item().path())
					.isEqualTo("org_actions_permissions.selected_repositories");
			assertThat(unfixed.reason())
					.isEqualTo("no repository unknown in my-org");
		});
		verify(
				0,
				putRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/repositories"
						)
				)
		);
	}

	/** The selected-actions allow-list is its own endpoint and its own fix. */
	@Test
	void theSelectedActionsFixWritesTheAllowList(WireMockRuntimeInfo wm) {
		stubFor(
				put(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/selected-actions"
						)
				).willReturn(aResponse().withStatus(204))
		);
		var group = new OrgActionsPermissionsDriftGroup(
				Desired.actionsPermissions()
						.withAllowedActions(Drifty.AllowedActions.SELECTED)
						.withSelectedActions(
								Desired.selectedActions()
										.withGithubOwnedAllowed(false)
										.withVerifiedAllowed(true)
										.withPatternsAllowed(
												List.of("octo/*@*")
										)
						),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.SELECTED,
						false,
						new ActualSelectedActions(true, false, List.of()),
						List.of()
				),
				Map.of(),
				new GitHubClient(wm.getHttpBaseUrl(), "test-token"),
				"my-org"
		);

		var selectedActions = group.detect()
				.stream()
				.filter(f -> !f.items().isEmpty())
				.findFirst()
				.orElseThrow();

		assertThat(selectedActions.fix().execute().unfixedItems()).isEmpty();
		verify(
				putRequestedFor(
						urlPathEqualTo(
								"/orgs/my-org/actions/permissions/selected-actions"
						)
				).withRequestBody(equalToJson("""
						{
						  "github_owned_allowed": false,
						  "verified_allowed": true,
						  "patterns_allowed": ["octo/*@*"]
						}"""))
		);
	}

}
