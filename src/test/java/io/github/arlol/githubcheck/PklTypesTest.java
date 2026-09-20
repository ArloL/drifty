package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.arlol.githubcheck.client.ApiContract;
import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.WireShape;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Pins {@link PklTypes}, the write direction of the three vocabularies
 * {@code ConfigSpellingTest} pins the read direction of. Every mapping there
 * answers the client constant whose name matches the {@code Drifty} member's,
 * and that identity is the rule this states — not a second copy of the arms.
 * <p>
 * Both failures it catches are invisible without it. An arm answering the wrong
 * constant of the right union ({@code ALWAYS -> PULL_REQUEST}) compiles, and
 * the four {@code valueOf(v.name())} mappings throw at runtime rather than at
 * build time when a schema member has no client constant. Neither reaches a
 * test that builds its desired state from the schema: the value is carried
 * intact all the way to the request body, so the run succeeds and GitHub is
 * told to do something the config never asked for.
 * <p>
 * The exhaustive {@code switch} is what makes a <em>missing</em> arm a compile
 * error, so it is only the mapping that is left to check — which is why this
 * iterates the {@code Drifty} union rather than a hand-written list of pairs.
 * The opposite direction, a client constant the schema has no member for, is
 * {@code ConfigSpellingTest}'s.
 */
class PklTypesTest {

	/**
	 * What {@code PUT /repos/{owner}/{repo}/pages} and {@code PATCH
	 * /orgs/{org}} accept, read from the vendored spec rather than repeated
	 * here — these two spellings reach GitHub as bare strings, so nothing else
	 * would notice them drifting.
	 */
	private static final ApiContract CONTRACT = ApiContract.bundled();

	@ParameterizedTest(name = "{0}")
	@MethodSource
	void mapsToTheClientConstantOfTheSameName(
			String member,
			String produced,
			String expected
	) {
		assertThat(produced).isEqualTo(expected);
	}

	static Stream<Arguments> mapsToTheClientConstantOfTheSameName() {
		return Stream.of(
				cases(Drifty.Visibility.class, PklTypes::visibility),
				cases(
						Drifty.WorkflowPermissions.class,
						PklTypes::workflowPermissions
				),
				cases(Drifty.AlertsThreshold.class, PklTypes::alertsThreshold),
				cases(
						Drifty.SecurityAlertsThreshold.class,
						PklTypes::securityAlertsThreshold
				),
				cases(Drifty.PatternOperator.class, PklTypes::patternOperator),
				cases(
						Drifty.SecretScanningBypassReviewerType.class,
						PklTypes::reviewerType
				),
				cases(Drifty.ActorType.class, PklTypes::actorType),
				cases(Drifty.BypassMode.class, PklTypes::bypassMode),
				cases(Drifty.RulesetTarget.class, PklTypes::rulesetTarget),
				cases(
						Drifty.RulesetEnforcement.class,
						PklTypes::rulesetEnforcement
				),
				cases(
						Drifty.SquashMergeCommitTitle.class,
						PklTypes::squashMergeCommitTitle
				),
				cases(
						Drifty.SquashMergeCommitMessage.class,
						PklTypes::squashMergeCommitMessage
				),
				cases(
						Drifty.MergeCommitTitle.class,
						PklTypes::mergeCommitTitle
				),
				cases(
						Drifty.MergeCommitMessage.class,
						PklTypes::mergeCommitMessage
				),
				cases(
						Drifty.ActionsEnabledRepositories.class,
						PklTypes::enabledRepositories
				),
				cases(
						Drifty.SecretVisibility.class,
						PklTypes::secretVisibility
				),
				cases(Drifty.AllowedActions.class, PklTypes::allowedActions)
		).flatMap(Function.identity());
	}

	private static <U extends Enum<U>, E extends Enum<E>> Stream<Arguments> cases(
			Class<U> union,
			Function<U, E> mapping
	) {
		return Arrays.stream(union.getEnumConstants())
				.map(
						member -> Arguments.of(
								union.getSimpleName() + "." + member.name(),
								mapping.apply(member).name(),
								member.name()
						)
				);
	}

	/**
	 * {@code Pages.buildType} is the one spelling with no {@code Drifty}
	 * constant — its union is written inline in the schema, so codegen leaves
	 * it a {@code String} and {@link PklTypes#pagesBuildType} upper-cases
	 * whatever the config said. The literal the schema declares therefore has
	 * to be GitHub's own, and a {@code @JsonProperty} rename on
	 * {@link PagesBuildType} would move one half of that without the other.
	 */
	@Test
	void translatesEveryBuildTypeGitHubAccepts() {
		Set<String> accepted = CONTRACT
				.schema("PUT /repos/{owner}/{repo}/pages", "request")
				.properties()
				.get("build_type")
				.enumValues();

		assertThat(accepted).isNotEmpty();
		assertThat(
				WireShape.enumValues(
						WireShape.clientMapper(),
						PagesBuildType.class
				)
		).containsExactlyInAnyOrderElementsOf(accepted);
		assertThat(
				accepted.stream()
						.map(PklTypes::pagesBuildType)
						.collect(Collectors.toSet())
		).containsExactlyInAnyOrder(PagesBuildType.values());
	}

	/**
	 * An organization's default repository permission is read and written as a
	 * bare field, so the schema's four literals are the only thing standing
	 * between the config and a 422.
	 */
	@Test
	void spellsEveryRepositoryPermissionGitHubAccepts() {
		Set<String> accepted = CONTRACT.schema("PATCH /orgs/{org}", "request")
				.properties()
				.get("default_repository_permission")
				.enumValues();

		assertThat(accepted).isNotEmpty();
		assertThat(
				Arrays.stream(Drifty.RepositoryPermission.values())
						.map(PklTypes::repositoryPermission)
						.collect(Collectors.toSet())
		).isEqualTo(accepted);
	}

}
