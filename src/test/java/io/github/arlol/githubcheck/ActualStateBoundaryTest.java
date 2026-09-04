package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualRepository;
import io.github.arlol.githubcheck.actual.ActualSecurityAndAnalysis;
import io.github.arlol.githubcheck.actual.ActualWorkflowPermissions;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.MergeCommitMessage;
import io.github.arlol.githubcheck.client.MergeCommitTitle;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RepositoryVisibility;
import io.github.arlol.githubcheck.client.SquashMergeCommitMessage;
import io.github.arlol.githubcheck.client.SquashMergeCommitTitle;
import io.github.arlol.githubcheck.client.WorkflowPermissions;
import io.github.arlol.githubcheck.drift.DriftGroup;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * The GitHub REST response records describe a wire format drifty does not own
 * and cannot version. Where they reach the drift groups, "how GitHub serialises
 * a ruleset" becomes knowledge spread across the comparison code, and swapping
 * the read path — for GraphQL bulk reads, say — stops being a change to the
 * client package.
 * <p>
 * {@code PklTypes} translates the desired side of the comparison into types
 * drifty owns; {@code ActualTypes} does the same for the actual side. These
 * tests pin that boundary for every drift group and for the state they read
 * from: the only client types allowed past it are the facade itself, the
 * repository reference, and the enums that spell GitHub's contract values,
 * which both sides of a comparison meet in.
 */
class ActualStateBoundaryTest {

	private static final String CLIENT_PACKAGE = "io.github.arlol.githubcheck.client";

	private static final Set<Class<?>> ALLOWED = Set
			.of(GitHubClient.class, RepoRef.class);

	@Test
	void noDriftGroupHoldsGitHubResponseTypes() {
		var offenders = new ArrayList<String>();
		for (DriftGroup<Drifty.GroupName> group : driftGroups()) {
			for (String type : clientTypesHeldBy(group.getClass())) {
				offenders.add(
						group.getClass().getSimpleName() + " holds " + type
				);
			}
		}
		assertThat(offenders).isEmpty();
	}

	@Test
	void repositoryStateHoldsNoGitHubResponseTypes() {
		assertThat(clientTypesHeldBy(RepositoryState.class))
				.as("client types reachable from RepositoryState's fields")
				.isEmpty();
	}

	@Test
	void organizationStateHoldsNoGitHubResponseTypes() {
		assertThat(clientTypesHeldBy(OrganizationState.class))
				.as("client types reachable from OrganizationState's fields")
				.isEmpty();
	}

	/**
	 * Every group the orchestrator would build; the fixture's values do not
	 * matter.
	 */
	private static List<DriftGroup<Drifty.GroupName>> driftGroups() {
		return new RepositoryChecker((String) null, false).createDriftGroups(
				new RepositoryState(
						new RepoRef("owner", "repo"),
						new ActualRepository(
								false,
								false,
								"",
								"",
								RepositoryVisibility.PUBLIC,
								"main",
								List.of(),
								true,
								true,
								true,
								false,
								false,
								true,
								false,
								true,
								true,
								true,
								false,
								false,
								false,
								SquashMergeCommitTitle.COMMIT_OR_PR_TITLE,
								SquashMergeCommitMessage.COMMIT_MESSAGES,
								MergeCommitTitle.MERGE_MESSAGE,
								MergeCommitMessage.PR_TITLE
						),
						new ActualSecurityAndAnalysis(
								false,
								false,
								false,
								false,
								false,
								false,
								false,
								false,
								List.of()
						),
						false,
						false,
						false,
						false,
						false,
						Map.of(),
						List.of(),
						List.of(),
						Map.of(),
						Map.of(),
						new ActualWorkflowPermissions(
								WorkflowPermissions.DefaultWorkflowPermissions.WRITE,
								true
						),
						Optional.empty()
				),
				Desired.repository("repo")
		);
	}

	/**
	 * The client types a class keeps as state, other than the allowed ones:
	 * looked for in its fields, inside collection and map type arguments, and
	 * inside any {@code actual.*} record it holds. Read from declared fields
	 * rather than record components: a record's fields are its components, and
	 * {@code getRecordComponents} needs reflection metadata the native image
	 * does not carry, so this test would pass on the JVM and fail natively.
	 */
	private static List<String> clientTypesHeldBy(Class<?> type) {
		var found = new ArrayList<String>();
		collect(type, found, new HashSet<>());
		return found;
	}

	private static void collect(
			Type type,
			List<String> found,
			Set<Class<?>> visited
	) {
		switch (type) {
		case Class<?> c -> {
			if (!visited.add(c)) {
				return;
			}
			if (c.getName().startsWith(CLIENT_PACKAGE) && !c.isEnum()
					&& !ALLOWED.contains(c)) {
				found.add(c.getSimpleName());
			} else if (c.getName().startsWith("io.github.arlol.githubcheck")
					&& !c.getName().startsWith(CLIENT_PACKAGE)) {
				for (Field field : c.getDeclaredFields()) {
					collect(field.getGenericType(), found, visited);
				}
			}
		}
		case ParameterizedType p -> {
			collect(p.getRawType(), found, visited);
			for (Type argument : p.getActualTypeArguments()) {
				collect(argument, found, visited);
			}
		}
		default -> {
			// Type variables and wildcards carry no concrete client type.
		}
		}
	}

}
