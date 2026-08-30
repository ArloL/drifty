package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.drift.BranchProtectionDriftGroup;
import io.github.arlol.githubcheck.drift.RulesetDriftGroup;

/**
 * The GitHub REST response records describe a wire format drifty does not own
 * and cannot version. Where they reach the drift groups, "how GitHub serialises
 * a ruleset" becomes knowledge spread across the comparison code, and swapping
 * the read path — for GraphQL bulk reads, say — stops being a change to the
 * client package.
 * <p>
 * {@code PklTypes} already translates the desired side of the comparison into
 * types drifty owns. These tests pin the same boundary on the actual side for
 * the two groups where the leakage ran deepest: between them,
 * {@link RulesetDriftGroup} and {@link BranchProtectionDriftGroup} held most of
 * the wire-shape navigation in the codebase.
 */
class ActualStateBoundaryTest {

	private static final String CLIENT_PACKAGE = "io.github.arlol.githubcheck.client";

	@Test
	void rulesetGroupHoldsNoGitHubResponseTypes() {
		assertThat(responseTypesHeldBy(RulesetDriftGroup.class)).isEmpty();
	}

	@Test
	void branchProtectionGroupHoldsNoGitHubResponseTypes() {
		assertThat(responseTypesHeldBy(BranchProtectionDriftGroup.class))
				.isEmpty();
	}

	@Test
	void repositoryStateExposesRulesetsAndProtectionsAsDriftyTypes() {
		assertThat(responseTypesHeldBy(RepositoryState.class))
				.as("RepositoryState fields that are GitHub response types")
				.doesNotContain(
						"RulesetDetailsResponse",
						"BranchProtectionResponse"
				);
	}

	/**
	 * The response types a class keeps as state, including the ones hidden
	 * inside collection and map type arguments. Read from declared fields
	 * rather than record components: a record's fields are its components, and
	 * {@code getRecordComponents} needs reflection metadata the native image
	 * does not carry, so this test would pass on the JVM and fail natively.
	 */
	private static List<String> responseTypesHeldBy(Class<?> type) {
		var types = new ArrayList<Type>();
		for (Field field : type.getDeclaredFields()) {
			types.add(field.getGenericType());
		}
		return responseTypesIn(types);
	}

	private static List<String> responseTypesIn(List<Type> types) {
		var found = new ArrayList<String>();
		types.forEach(type -> collectResponseTypes(type, found));
		return found;
	}

	private static void collectResponseTypes(Type type, List<String> found) {
		switch (type) {
		case Class<?> c -> {
			if (c.getName().startsWith(CLIENT_PACKAGE)
					&& c.getSimpleName().endsWith("Response")) {
				found.add(c.getSimpleName());
			}
		}
		case ParameterizedType p -> {
			collectResponseTypes(p.getRawType(), found);
			for (Type argument : p.getActualTypeArguments()) {
				collectResponseTypes(argument, found);
			}
		}
		default -> {
			// Type variables and wildcards carry no concrete response type.
		}
		}
	}

}
