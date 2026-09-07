package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.FetchFailures;
import io.github.arlol.githubcheck.OrganizationState;
import io.github.arlol.githubcheck.actual.ActualCustomProperty;
import io.github.arlol.githubcheck.actual.ActualOrgActionsPermissions;
import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
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

}
