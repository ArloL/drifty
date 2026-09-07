package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.FetchFailures;
import io.github.arlol.githubcheck.OrganizationState;
import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.client.WorkflowPermissions.DefaultWorkflowPermissions;
import io.github.arlol.githubcheck.testsupport.Actual;

class AccountExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	/** GitHub's defaults for a freshly created organization, nothing set. */
	private static OrganizationState defaultState(String login) {
		return new OrganizationState(
				login,
				Actual.organization(),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.ALL,
						AllowedActions.ALL,
						false,
						null
				),
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.WRITE,
						true
				),
				List.of()
		);
	}

	/** {@code target}/{@code enforcement} at their schema default too. */
	private static ActualRuleset ruleset(String name, String target) {
		return new ActualRuleset(
				1L,
				name,
				target,
				"active",
				Set.of(),
				Set.of(),
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				Set.of(),
				null,
				Set.of(),
				Set.of(),
				"",
				"",
				"",
				"",
				"",
				null,
				Set.of(),
				Set.of(),
				null,
				Set.of(),
				null,
				List.of(),
				Set.of(),
				Set.of(),
				false,
				Set.of(),
				Set.of()
		);
	}

	private static ActualTeam team(String slug) {
		return new ActualTeam(
				1L,
				slug,
				slug,
				"",
				"closed",
				"notifications_enabled",
				null,
				Set.of(),
				Set.of()
		);
	}

	/** Wraps a single entry the way a real file nests it under the key. */
	private static String render(PklNode.Member entry) {
		var outer = new PklNode.Obj(
				List.of(
						new PklNode.Field(
								"organizations",
								new PklNode.Mapping(List.of(entry))
						)
				)
		);
		return PklWriter.write(outer);
	}

	@Test
	void anUntouchedOrganizationExportsAnEmptyEntry() {
		var entry = AccountExporter.organization(
				defaultState("acme"),
				List.of(),
				List.of(),
				DEFAULTS
		);

		assertThat(render(entry)).isEqualTo("""
				organizations {
				  ["acme"] {
				  }
				}
				""");
	}

	@Test
	void aFailureBecomesANoteWhereTheGroupWouldSit() {
		var base = defaultState("acme");
		var state = new OrganizationState(
				base.login(),
				base.settings(),
				base.actionsPermissions(),
				base.workflowPermissions(),
				List.of(),
				List.of(),
				List.of(),
				List.of(
						new ActualCustomProperty(
								"priority",
								"string",
								true,
								null,
								List.of(),
								"",
								List.of(),
								"org_actors"
						)
				),
				List.of(),
				List.of(),
				List.of(),
				List.of(new ActualOrgMember("alice", "admin")),
				List.of()
		);
		var failures = List.of(
				new FetchFailures.Failure(
						"org_teams",
						"the token cannot read teams"
				)
		);

		var entry = AccountExporter
				.organization(state, failures, List.of(), DEFAULTS);

		assertThat(render(entry)).isEqualTo("""
				organizations {
				  ["acme"] {
				    customProperties {
				      ["priority"] {
				        valueType = "string"
				        required = true
				      }
				    }
				    // org_teams: the token cannot read teams
				    members {
				      ["alice"] = "admin"
				    }
				  }
				}
				""");
	}

	@Test
	void aTeamAndARulesetNestUnderTheLogin() {
		var base = defaultState("acme");
		var state = new OrganizationState(
				base.login(),
				base.settings(),
				base.actionsPermissions(),
				base.workflowPermissions(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(ruleset("main", "branch")),
				List.of(),
				List.of(team("platform")),
				List.of(),
				List.of()
		);

		var entry = AccountExporter
				.organization(state, List.of(), List.of(), DEFAULTS);

		assertThat(render(entry)).isEqualTo("""
				organizations {
				  ["acme"] {
				    rulesets {
				      ["main"] {
				      }
				    }
				    teams {
				      ["platform"] {
				      }
				    }
				  }
				}
				""");
	}

	/**
	 * The four {@code wire(...)} conversions in {@link AccountExporter} are
	 * explicit switches over client enums with no {@code Drifty.*} equivalent
	 * to fall back on, so nothing else in the codebase constrains their
	 * strings. This covers {@code SELECTED} for enabled-repositories,
	 * allowed-actions and secret visibility, plus {@code ALL} for variable
	 * visibility and {@code READ} for workflow permissions — the branches
	 * {@link #anUntouchedOrganizationExportsAnEmptyEntry} cannot reach because
	 * every field there sits at its default.
	 */
	@Test
	void nonDefaultActionsEnumsAreSpelledCorrectly() {
		var base = defaultState("acme");
		var state = new OrganizationState(
				base.login(),
				base.settings(),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.SELECTED,
						AllowedActions.SELECTED,
						false,
						null,
						List.of("api")
				),
				new ActualWorkflowPermissions(
						DefaultWorkflowPermissions.READ,
						true
				),
				List.of(
						new ActualOrgSecret(
								"ci-token",
								"2024-01-01T00:00:00Z",
								SecretVisibility.SELECTED,
								List.of("api")
						)
				),
				List.of(
						new ActualOrgVariable(
								"region",
								"us-east-1",
								SecretVisibility.ALL,
								List.of()
						)
				),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of()
		);

		var entry = AccountExporter
				.organization(state, List.of(), List.of(), DEFAULTS);

		assertThat(render(entry)).isEqualTo(
				"""
						organizations {
						  ["acme"] {
						    actionsPermissions {
						      enabledRepositories = "selected"
						      allowedActions = "selected"
						      selectedRepositories {
						        "api"
						      }
						    }
						    defaultWorkflowPermissions = "read"
						    actionsSecrets {
						      ["ci-token"] {
						        visibility = "selected"
						        selectedRepositories {
						          "api"
						        }
						      }
						      // secret values are never returned by GitHub; supply them through
						      // DRIFTY_GITHUB_SECRETS
						    }
						    actionsVariables {
						      ["region"] {
						        value = "us-east-1"
						        visibility = "all"
						      }
						    }
						  }
						}
						"""
		);
	}

	/**
	 * The three branches {@link #nonDefaultActionsEnumsAreSpelledCorrectly}
	 * cannot reach: {@code NONE} and {@code LOCAL_ONLY} render visibly, and
	 * {@code PRIVATE} is GitHub's own default for a secret, so it is only
	 * verified by the field it produces staying suppressed — a wrong spelling
	 * there would make it differ from the schema default and render a spurious
	 * {@code visibility} field instead.
	 */
	@Test
	void remainingActionsEnumBranchesAreSpelledCorrectly() {
		var base = defaultState("acme");
		var state = new OrganizationState(
				base.login(),
				base.settings(),
				new ActualOrgActionsPermissions(
						ActionsEnabledRepositories.NONE,
						AllowedActions.LOCAL_ONLY,
						false,
						null
				),
				base.workflowPermissions(),
				List.of(
						new ActualOrgSecret(
								"ci-token",
								"2024-01-01T00:00:00Z",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of(),
				List.of()
		);

		var entry = AccountExporter
				.organization(state, List.of(), List.of(), DEFAULTS);

		assertThat(render(entry)).isEqualTo(
				"""
						organizations {
						  ["acme"] {
						    actionsPermissions {
						      enabledRepositories = "none"
						      allowedActions = "local_only"
						    }
						    actionsSecrets {
						      ["ci-token"] {
						      }
						      // secret values are never returned by GitHub; supply them through
						      // DRIFTY_GITHUB_SECRETS
						    }
						  }
						}
						"""
		);
	}

	/**
	 * Sorting ensures exported files are deterministic: a second export of the
	 * same account with unchanged settings must produce identical output, with
	 * entries appearing in sort order by their keys. This verifies both
	 * properties.
	 */
	@Test
	void allCollectionsAreSortedSoExportsAreDeterministic() {
		var base = defaultState("acme");
		var state = new OrganizationState(
				base.login(),
				base.settings(),
				base.actionsPermissions(),
				base.workflowPermissions(),
				List.of(
						new ActualOrgSecret(
								"z-secret",
								"2024-01-01T00:00:00Z",
								SecretVisibility.PRIVATE,
								List.of()
						),
						new ActualOrgSecret(
								"a-secret",
								"2024-01-01T00:00:00Z",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				List.of(
						new ActualOrgVariable(
								"z-var",
								"value",
								SecretVisibility.PRIVATE,
								List.of()
						),
						new ActualOrgVariable(
								"a-var",
								"value",
								SecretVisibility.PRIVATE,
								List.of()
						)
				),
				List.of(
						new ActualWebhook(
								1L,
								"https://example.com/z",
								"json",
								false,
								true,
								Set.of("push"),
								false,
								"2024-01-01T00:00:00Z"
						),
						new ActualWebhook(
								2L,
								"https://example.com/a",
								"json",
								false,
								true,
								Set.of("push"),
								false,
								"2024-01-01T00:00:00Z"
						)
				),
				List.of(
						new ActualCustomProperty(
								"z-prop",
								"string",
								true,
								null,
								List.of(),
								"",
								List.of(),
								"org_actors"
						),
						new ActualCustomProperty(
								"a-prop",
								"string",
								true,
								null,
								List.of(),
								"",
								List.of(),
								"org_actors"
						)
				),
				List.of(
						ruleset("z-ruleset", "branch"),
						ruleset("a-ruleset", "branch")
				),
				List.of(
						new ActualCodeSecurityConfiguration(
								1L,
								"z-config",
								"",
								Map.of(),
								"active",
								false,
								null,
								null,
								null,
								Set.of(),
								"false",
								Set.of()
						),
						new ActualCodeSecurityConfiguration(
								2L,
								"a-config",
								"",
								Map.of(),
								"active",
								false,
								null,
								null,
								null,
								Set.of(),
								"false",
								Set.of()
						)
				),
				List.of(team("z-team"), team("a-team")),
				List.of(
						new ActualOrgMember("z-user", "member"),
						new ActualOrgMember("a-user", "admin")
				),
				List.of(
						new ActualRunnerGroup(
								1L,
								"z-group",
								"public",
								false,
								false,
								false,
								Set.of(),
								List.of()
						),
						new ActualRunnerGroup(
								2L,
								"a-group",
								"public",
								false,
								false,
								false,
								Set.of(),
								List.of()
						)
				)
		);

		var entry = AccountExporter
				.organization(state, List.of(), List.of(), DEFAULTS);
		var firstExport = render(entry);

		// Re-export the same state to verify the output is identical
		var secondExport = render(entry);
		assertThat(firstExport).isEqualTo(secondExport);

		// Verify entries appear in sorted order
		var aSecretPos = firstExport.indexOf("[\"a-secret\"]");
		var zSecretPos = firstExport.indexOf("[\"z-secret\"]");
		assertThat(aSecretPos).isGreaterThan(0);
		assertThat(aSecretPos).isLessThan(zSecretPos);

		var aVarPos = firstExport.indexOf("[\"a-var\"]");
		var zVarPos = firstExport.indexOf("[\"z-var\"]");
		assertThat(aVarPos).isGreaterThan(0);
		assertThat(aVarPos).isLessThan(zVarPos);

		var aWebhookPos = firstExport.indexOf("[\"example.com/a\"]");
		var zWebhookPos = firstExport.indexOf("[\"example.com/z\"]");
		assertThat(aWebhookPos).isGreaterThan(0);
		assertThat(aWebhookPos).isLessThan(zWebhookPos);

		var aPropPos = firstExport.indexOf("[\"a-prop\"]");
		var zPropPos = firstExport.indexOf("[\"z-prop\"]");
		assertThat(aPropPos).isGreaterThan(0);
		assertThat(aPropPos).isLessThan(zPropPos);

		var aRulesetPos = firstExport.indexOf("[\"a-ruleset\"]");
		var zRulesetPos = firstExport.indexOf("[\"z-ruleset\"]");
		assertThat(aRulesetPos).isGreaterThan(0);
		assertThat(aRulesetPos).isLessThan(zRulesetPos);

		var aConfigPos = firstExport.indexOf("[\"a-config\"]");
		var zConfigPos = firstExport.indexOf("[\"z-config\"]");
		assertThat(aConfigPos).isGreaterThan(0);
		assertThat(aConfigPos).isLessThan(zConfigPos);

		var aTeamPos = firstExport.indexOf("[\"a-team\"]");
		var zTeamPos = firstExport.indexOf("[\"z-team\"]");
		assertThat(aTeamPos).isGreaterThan(0);
		assertThat(aTeamPos).isLessThan(zTeamPos);

		var aUserPos = firstExport.indexOf("[\"a-user\"]");
		var zUserPos = firstExport.indexOf("[\"z-user\"]");
		assertThat(aUserPos).isGreaterThan(0);
		assertThat(aUserPos).isLessThan(zUserPos);

		var aGroupPos = firstExport.indexOf("[\"a-group\"]");
		var zGroupPos = firstExport.indexOf("[\"z-group\"]");
		assertThat(aGroupPos).isGreaterThan(0);
		assertThat(aGroupPos).isLessThan(zGroupPos);
	}

}
