package io.github.arlol.githubcheck.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RepositoryArgsTest {

	@Test
	void toBuilderInheritsAllFields() {
		var original = RepositoryArgs.create("original")
				.description("A description")
				.pages()
				.requiredStatusChecks("base-check")
				.build();

		var copy = original.toBuilder().name("copy").build();

		assertEquals("copy", copy.name());
		assertEquals("A description", copy.description());
		assertTrue(copy.pages());
		assertEquals(List.of("base-check"), copy.requiredStatusChecks());
	}

	@Test
	void nameSetterOverridesName() {
		var defaults = RepositoryArgs.create("_").pages().build();

		var repo = defaults.toBuilder().name("my-repo").build();

		assertEquals("my-repo", repo.name());
		assertTrue(repo.pages());
	}

	@Test
	void requiredStatusChecksReplacesList() {
		var base = RepositoryArgs.create("repo")
				.requiredStatusChecks("old-check")
				.build();

		var updated = base.toBuilder()
				.requiredStatusChecks("new-check")
				.build();

		assertEquals(List.of("new-check"), updated.requiredStatusChecks());
	}

	@Test
	void addRequiredStatusChecksAppends() {
		var base = RepositoryArgs.create("repo")
				.requiredStatusChecks("base-check")
				.build();

		var extended = base.toBuilder()
				.addRequiredStatusChecks("extra-check")
				.build();

		assertEquals(
				List.of("base-check", "extra-check"),
				extended.requiredStatusChecks()
		);
	}

	@Test
	void addRequiredStatusChecksOnEmptyList() {
		var base = RepositoryArgs.create("repo").build();

		var extended = base.toBuilder()
				.addRequiredStatusChecks("first-check")
				.build();

		assertEquals(List.of("first-check"), extended.requiredStatusChecks());
	}

	@Test
	void addRequiredStatusChecksDoesNotMutateOriginal() {
		var base = RepositoryArgs.create("repo")
				.requiredStatusChecks("base-check")
				.build();

		base.toBuilder().addRequiredStatusChecks("extra-check").build();

		assertEquals(List.of("base-check"), base.requiredStatusChecks());
	}

	@Test
	void groupDefaultNotAffectedByPerRepoOverride() {
		var groupDefault = RepositoryArgs.create("_")
				.requiredStatusChecks("main.required-status-check")
				.build();

		var repoA = groupDefault.toBuilder().name("repo-a").build();
		var repoB = groupDefault.toBuilder()
				.name("repo-b")
				.addRequiredStatusChecks("extra-check")
				.build();

		assertEquals(
				List.of("main.required-status-check"),
				repoA.requiredStatusChecks()
		);
		assertEquals(
				List.of("main.required-status-check", "extra-check"),
				repoB.requiredStatusChecks()
		);
		// group default is unchanged
		assertEquals(
				List.of("main.required-status-check"),
				groupDefault.requiredStatusChecks()
		);
		assertFalse(groupDefault.pages());
	}

}
