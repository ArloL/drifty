package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.arlol.githubcheck.client.ActionsEnabledRepositories;
import io.github.arlol.githubcheck.client.AllowedActions;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.RulePatternOperator;
import io.github.arlol.githubcheck.client.RulesetDetailsResponse;
import io.github.arlol.githubcheck.client.RulesetEnforcement;
import io.github.arlol.githubcheck.client.RulesetTarget;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.client.TeamResponse;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Pins every client enum whose spelling ends up in an exported config file
 * against the union {@code config/drifty.pkl} declares for it.
 * <p>
 * An exported file is read back by Pkl, so a constant spelled anything but the
 * union's own literal produces a file that does not evaluate — discovered by
 * the adopter running drifty against the config drifty just wrote, not by the
 * export. Three vocabularies are in play and only one of them is right here:
 * the Java constant name ({@code ORGANIZATION_ADMIN}), GitHub's wire spelling
 * ({@code @JsonProperty}), and the schema's literal, which is what
 * {@code Drifty.*}'s {@code toString()} gives.
 * <p>
 * {@code ConfigSpelling} answers with a {@code Drifty} constant rather than a
 * string, so a misspelling cannot compile and a renamed union member breaks the
 * build. What is left for this test is the mapping: an arm answering the wrong
 * constant of the right union compiles and is wrong, and so is a client
 * constant whose union has no member of that name.
 */
class ConfigSpellingTest {

	@ParameterizedTest(name = "{0}")
	@MethodSource
	void spellsWhatTheSchemaDeclares(
			String constant,
			String produced,
			String declared
	) {
		assertThat(produced).isEqualTo(declared);
	}

	static Stream<Arguments> spellsWhatTheSchemaDeclares() {
		return Stream
				.of(
						cases(
								RulesetTarget.values(),
								Drifty.RulesetTarget.class,
								ConfigSpelling::of
						),
						cases(
								RulesetEnforcement.values(),
								Drifty.RulesetEnforcement.class,
								ConfigSpelling::of
						),
						cases(
								RulePatternOperator.values(),
								Drifty.PatternOperator.class,
								ConfigSpelling::of
						),
						cases(
								RulesetDetailsResponse.BypassActor.ActorType
										.values(),
								Drifty.ActorType.class,
								ConfigSpelling::of
						),
						cases(
								RulesetDetailsResponse.BypassActor.BypassMode
										.values(),
								Drifty.BypassMode.class,
								ConfigSpelling::of
						),
						cases(
								TeamResponse.Privacy.values(),
								Drifty.TeamPrivacy.class,
								ConfigSpelling::of
						),
						cases(
								TeamResponse.NotificationSetting.values(),
								Drifty.TeamNotificationSetting.class,
								ConfigSpelling::of
						),
						cases(
								ActionsEnabledRepositories.values(),
								Drifty.ActionsEnabledRepositories.class,
								ConfigSpelling::of
						),
						cases(
								AllowedActions.values(),
								Drifty.AllowedActions.class,
								ConfigSpelling::of
						),
						cases(
								SecretVisibility.values(),
								Drifty.SecretVisibility.class,
								ConfigSpelling::of
						),
						cases(
								WorkflowPermissions.DefaultWorkflowPermissions
										.values(),
								Drifty.WorkflowPermissions.class,
								ConfigSpelling::of
						),
						cases(
								RepositoryVisibility.values(),
								Drifty.Visibility.class,
								ConfigSpelling::of
						)
				)
				.flatMap(Function.identity());
	}

	/**
	 * The union member is looked up by the client constant's own name, which is
	 * what makes this a test of the mapping rather than a second copy of it.
	 * {@code Enum.valueOf} throwing is the failure for a client constant the
	 * schema has no member for.
	 */
	private static <E extends Enum<E>, U extends Enum<U>> Stream<Arguments> cases(
			E[] values,
			Class<U> union,
			Function<E, String> spelling
	) {
		return Arrays.stream(values)
				.map(
						value -> Arguments.of(
								value.getDeclaringClass().getSimpleName() + "."
										+ value.name(),
								spelling.apply(value),
								Enum.valueOf(union, value.name()).toString()
						)
				);
	}

}
