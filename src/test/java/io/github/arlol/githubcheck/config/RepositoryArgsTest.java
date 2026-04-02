package io.github.arlol.githubcheck.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RepositoryArgsTest {

	@Test
	void toBuilderInheritsAllFields() {
		var original = RepositoryArgs.create("owner", "original")
				.description("A description")
				.pages()
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();

		var copy = original.toBuilder().name("copy").build();

		assertEquals("copy", copy.name());
		assertEquals("A description", copy.description());
		assertTrue(copy.pages());
		assertEquals(
				Map.of("main", BranchProtectionArgs.builder("main").build()),
				copy.branchProtections()
		);
	}

	@Test
	void nameSetterOverridesName() {
		var defaults = RepositoryArgs.create("owner", "default")
				.pages()
				.build();

		var repo = defaults.toBuilder().name("my-repo").build();

		assertEquals("my-repo", repo.name());
		assertTrue(repo.pages());
	}

	@Test
	void branchProtectionsReplacesList() {
		var base = RepositoryArgs.create("owner", "repo")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();

		var updated = base.toBuilder()
				.branchProtections(
						BranchProtectionArgs.builder("master").build()
				)
				.build();

		assertThat(updated.branchProtections().values()).containsExactly(
				BranchProtectionArgs.builder("master").build()
		);
	}

	@Test
	void addBranchProtectionsAppends() {
		var base = RepositoryArgs.create("owner", "repo")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();

		var extended = base.toBuilder()
				.addBranchProtections(
						BranchProtectionArgs.builder("master").build()
				)
				.build();

		assertThat(extended.branchProtections().values())
				.containsExactlyInAnyOrder(
						BranchProtectionArgs.builder("main").build(),
						BranchProtectionArgs.builder("master").build()
				);
	}

	@Test
	void addBranchProtectionsOnEmptyList() {
		var base = RepositoryArgs.create("owner", "repo").build();

		var extended = base.toBuilder()
				.addBranchProtections(
						BranchProtectionArgs.builder("main").build()
				)
				.build();

		assertThat(extended.branchProtections().values())
				.containsExactly(BranchProtectionArgs.builder("main").build());
	}

	@Test
	void addBranchProtectionsDoesNotMutateOriginal() {
		var base = RepositoryArgs.create("owner", "repo")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();

		base.toBuilder()
				.addBranchProtections(
						BranchProtectionArgs.builder("master").build()
				)
				.build();

		assertThat(base.branchProtections().values())
				.containsExactly(BranchProtectionArgs.builder("main").build());
	}

	@Test
	void groupDefaultNotAffectedByPerRepoOverride() {
		var groupDefault = RepositoryArgs.create("owner", "default")
				.branchProtections(BranchProtectionArgs.builder("main").build())
				.build();

		var repoA = groupDefault.toBuilder().name("repo-a").build();
		var repoB = groupDefault.toBuilder()
				.name("repo-b")
				.addBranchProtections(
						BranchProtectionArgs.builder("master").build()
				)
				.build();

		assertThat(repoA.branchProtections().values())
				.containsExactly(BranchProtectionArgs.builder("main").build());

		assertThat(repoB.branchProtections().values())
				.containsExactlyInAnyOrder(
						BranchProtectionArgs.builder("main").build(),
						BranchProtectionArgs.builder("master").build()
				);

		// group default is unchanged
		assertThat(groupDefault.branchProtections().values())
				.containsExactly(BranchProtectionArgs.builder("main").build());
		assertFalse(groupDefault.pages());
	}

}
