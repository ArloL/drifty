package io.github.arlol.githubcheck.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class RepositoryArgsTest {

	@Test
	void toBuilderInheritsAllFields() {
		var statusCheck = StatusCheckArgs.builder()
				.context("base-check")
				.build();
		var original = RepositoryArgs.create("original")
				.description("A description")
				.pages()
				.requiredStatusChecks(statusCheck)
				.build();

		var copy = original.toBuilder().name("copy").build();

		assertEquals("copy", copy.name());
		assertEquals("A description", copy.description());
		assertTrue(copy.pages());
		assertEquals(Set.of(statusCheck), copy.requiredStatusChecks());
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
				.requiredStatusChecks(
						StatusCheckArgs.builder().context("old-check").build()
				)
				.build();

		var updated = base.toBuilder()
				.requiredStatusChecks(
						StatusCheckArgs.builder().context("new-check").build()
				)
				.build();

		assertEquals(
				Set.of(StatusCheckArgs.builder().context("new-check").build()),
				updated.requiredStatusChecks()
		);
	}

	@Test
	void addRequiredStatusChecksAppends() {
		var base = RepositoryArgs.create("repo")
				.requiredStatusChecks(
						StatusCheckArgs.builder().context("base-check").build()
				)
				.build();

		var extended = base.toBuilder()
				.addRequiredStatusChecks(
						StatusCheckArgs.builder().context("extra-check").build()
				)
				.build();

		assertEquals(
				Set.of(
						StatusCheckArgs.builder().context("base-check").build(),
						StatusCheckArgs.builder().context("extra-check").build()
				),
				extended.requiredStatusChecks()
		);
	}

	@Test
	void addRequiredStatusChecksOnEmptyList() {
		var base = RepositoryArgs.create("repo").build();

		var extended = base.toBuilder()
				.addRequiredStatusChecks(
						StatusCheckArgs.builder().context("first-check").build()
				)
				.build();

		assertEquals(
				Set.of(
						StatusCheckArgs.builder().context("first-check").build()
				),
				extended.requiredStatusChecks()
		);
	}

	@Test
	void addRequiredStatusChecksDoesNotMutateOriginal() {
		var base = RepositoryArgs.create("repo")
				.requiredStatusChecks(
						StatusCheckArgs.builder().context("base-check").build()
				)
				.build();

		base.toBuilder()
				.addRequiredStatusChecks(
						StatusCheckArgs.builder().context("extra-check").build()
				)
				.build();

		assertEquals(
				Set.of(StatusCheckArgs.builder().context("base-check").build()),
				base.requiredStatusChecks()
		);
	}

	@Test
	void groupDefaultNotAffectedByPerRepoOverride() {
		var groupDefault = RepositoryArgs.create("_")
				.requiredStatusChecks(
						StatusCheckArgs.builder()
								.context("main.required-status-check")
								.build()
				)
				.build();

		var repoA = groupDefault.toBuilder().name("repo-a").build();
		var repoB = groupDefault.toBuilder()
				.name("repo-b")
				.addRequiredStatusChecks(
						StatusCheckArgs.builder().context("extra-check").build()
				)
				.build();

		assertEquals(
				Set.of(
						StatusCheckArgs.builder()
								.context("main.required-status-check")
								.build()
				),
				repoA.requiredStatusChecks()
		);
		assertEquals(
				Set.of(
						StatusCheckArgs.builder()
								.context("main.required-status-check")
								.build(),
						StatusCheckArgs.builder().context("extra-check").build()
				),
				repoB.requiredStatusChecks()
		);
		// group default is unchanged
		assertEquals(
				Set.of(
						StatusCheckArgs.builder()
								.context("main.required-status-check")
								.build()
				),
				groupDefault.requiredStatusChecks()
		);
		assertFalse(groupDefault.pages());
	}

}
