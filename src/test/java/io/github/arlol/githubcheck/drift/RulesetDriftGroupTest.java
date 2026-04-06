package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.Rule;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.config.RulesetArgs;
import io.github.arlol.githubcheck.config.StatusCheckArgs;

class RulesetDriftGroupTest {

	private static RulesetDetailsResponse matchingResponse(String name) {
		return new RulesetDetailsResponse(
				1L,
				name,
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				new RulesetDetailsResponse.Conditions(
						new RulesetDetailsResponse.Conditions.RefName(
								List.of(),
								List.of()
						),
						null,
						null,
						null
				),
				List.of()
		);
	}

	@Test
	void noDrift_whenBothEmpty() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsExtraRuleset() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(matchingResponse("ci")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(items.getFirst().message())
				.isEqualTo("ruleset.ci: extra (should not exist)");
	}

	@Test
	void detectsMissingRuleset() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(RulesetArgs.builder("ci").build())
				.build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message()).isEqualTo("ruleset.ci: missing");
	}

	@Test
	void noDrift_whenRulesetsMatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(RulesetArgs.builder("ci").build())
				.build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(matchingResponse("ci")),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingIncludePattern() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.includePatterns("refs/heads/main")
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(matchingResponse("ci")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.path()).isEqualTo("ruleset.ci.include_patterns");
		assertThat(drift.missing()).hasSize(1);
		assertThat(drift.message()).contains("refs/heads/main");
	}

	@Test
	void detectsRequiredLinearHistoryDrift() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.requiredLinearHistory(true)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(matchingResponse("ci")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		assertThat(items.getFirst().message()).isEqualTo(
				"ruleset.ci.required_linear_history: want=true got=false"
		);
	}

	@Test
	void noDrift_whenRequiredLinearHistoryMatches() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.requiredLinearHistory(true)
								.build()
				)
				.build();
		var actual = new RulesetDetailsResponse(
				1L,
				"ci",
				RulesetTarget.BRANCH,
				RulesetEnforcement.ACTIVE,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				new RulesetDetailsResponse.Conditions(
						new RulesetDetailsResponse.Conditions.RefName(
								List.of(),
								List.of()
						),
						null,
						null,
						null
				),
				List.of(new Rule.RequiredLinearHistory())
		);
		var group = new RulesetDriftGroup(
				desired,
				List.of(actual),
				null,
				"owner",
				"repo"
		);

		assertThat(group.detect()).isEmpty();
	}

	@Test
	void detectsMissingStatusCheck() {
		var check = StatusCheckArgs.builder().context("build").build();
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(
						RulesetArgs.builder("ci")
								.requiredStatusChecks(check)
								.build()
				)
				.build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(matchingResponse("ci")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst()).isInstanceOf(DriftItem.SetDrift.class);
		var drift = (DriftItem.SetDrift) items.getFirst();
		assertThat(drift.path()).isEqualTo("ruleset.ci.required_status_checks");
		assertThat(drift.missing()).hasSize(1);
	}

	@Test
	void detectsExtraAndMissingRuleset() {
		var desired = RepositoryArgs.create("owner", "repo")
				.rulesets(RulesetArgs.builder("new-ruleset").build())
				.build();
		var group = new RulesetDriftGroup(
				desired,
				List.of(matchingResponse("old-ruleset")),
				null,
				"owner",
				"repo"
		);

		var items = group.detect();

		assertThat(items).hasSize(2);
		assertThat(items).anyMatch(i -> i instanceof DriftItem.SectionMissing);
		assertThat(items).anyMatch(i -> i instanceof DriftItem.SectionExtra);
	}

}
