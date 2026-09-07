package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualEnvironment;
import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.actual.ActualVariable;

class EnvironmentExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static ActualEnvironment defaultEnvironment() {
		return new ActualEnvironment(
				0,
				false,
				Set.of(),
				false,
				false,
				List.of()
		);
	}

	private static String render(
			ActualEnvironment actual,
			List<ActualSecret> secrets,
			List<ActualVariable> variables
	) {
		var field = (PklNode.Field) EnvironmentExporter.entry(
				"production",
				actual,
				secrets,
				variables,
				DEFAULTS.environment()
		);
		return PklWriter.write(field.value());
	}

	@Test
	void everyFieldAtItsDefaultExportsNothing() {
		assertThat(render(defaultEnvironment(), List.of(), List.of()))
				.isEqualTo("");
	}

	@Test
	void keyIsTheEnvironmentName() {
		var field = (PklNode.Field) EnvironmentExporter.entry(
				"production",
				defaultEnvironment(),
				List.of(),
				List.of(),
				DEFAULTS.environment()
		);
		assertThat(field.name()).isEqualTo("production");
	}

	@Test
	void waitTimerAndPreventSelfReviewAreEmittedWhenDrifted() {
		ActualEnvironment actual = new ActualEnvironment(
				10,
				true,
				Set.of(),
				false,
				false,
				List.of()
		);
		assertThat(render(actual, List.of(), List.of())).isEqualTo("""
				waitTimer = 10
				preventSelfReview = true
				""");
	}

	/**
	 * {@code ActualTypes.reviewerKey} prefixes a login with {@code User:} and a
	 * slug with {@code Team:}; a wrong prefix (or a swapped split) would either
	 * misfile one into the other's field or leave both empty.
	 */
	@Test
	void reviewersAreSplitByTheirActualTypesPrefix() {
		ActualEnvironment actual = new ActualEnvironment(
				0,
				false,
				Set.of("User:alice", "Team:platform"),
				false,
				false,
				List.of()
		);
		assertThat(render(actual, List.of(), List.of())).isEqualTo("""
				reviewerUsers = new Listing {
				  "alice"
				}
				reviewerTeams = new Listing {
				  "platform"
				}
				""");
	}

	@Test
	void branchPoliciesAreSplitByType() {
		ActualEnvironment actual = new ActualEnvironment(
				0,
				false,
				Set.of(),
				false,
				true,
				List.of(
						new ActualEnvironment.BranchPolicy(
								1L,
								"branch",
								"main"
						),
						new ActualEnvironment.BranchPolicy(2L, "tag", "v*")
				)
		);
		assertThat(render(actual, List.of(), List.of())).isEqualTo("""
				customBranchPolicies = true
				deploymentBranchPatterns = new Listing {
				  "main"
				}
				deploymentTagPatterns = new Listing {
				  "v*"
				}
				""");
	}

	@Test
	void secretsAreEmittedAsNamesWithTheValueNote() {
		assertThat(
				render(
						defaultEnvironment(),
						List.of(
								new ActualSecret(
										"token",
										"2024-01-01T00:00:00Z"
								)
						),
						List.of()
				)
		).isEqualTo(
				"""
						secrets = new Listing {
						  "token"
						}
						// secret values are never returned by GitHub; supply them through
						// DRIFTY_GITHUB_SECRETS
						"""
		);
	}

	@Test
	void variablesAreEmittedAsNameToValueSortedByName() {
		assertThat(
				render(
						defaultEnvironment(),
						List.of(),
						List.of(
								new ActualVariable("zebra", "z"),
								new ActualVariable("alpha", "a")
						)
				)
		).isEqualTo("""
				variables {
				  ["alpha"] = "a"
				  ["zebra"] = "z"
				}
				""");
	}

	@Test
	void noSecretsMeansNoNote() {
		assertThat(render(defaultEnvironment(), List.of(), List.of()))
				.doesNotContain("DRIFTY_GITHUB_SECRETS");
	}

}
