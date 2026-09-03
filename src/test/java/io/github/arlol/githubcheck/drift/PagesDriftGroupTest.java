package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.testsupport.Desired;

class PagesDriftGroupTest {

	@Test
	void noDriftWhenPagesNotDesired() {
		var desired = Desired.repository("owner", "repo");
		var actual = Optional
				.of(new ActualPages("workflow", Optional.empty(), true));
		var group = new PagesDriftGroup(
				desired.pages,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

	@Test
	void detectsMissingPages() {
		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.pages());
		Optional<ActualPages> actual = Optional.empty();
		var group = new PagesDriftGroup(
				desired.pages,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message()).isEqualTo("pages: missing");
	}

	@Test
	void detectsBuildTypeMismatch() {
		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.pages()); // wants workflow
		var actual = Optional.of(
				new ActualPages(
						"legacy",
						Optional.of(new ActualPages.Source("gh-pages", "/")),
						true
				)
		);
		var group = new PagesDriftGroup(
				desired.pages,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("pages.build_type");
		assertThat(drift.wanted()).isEqualTo("workflow");
		assertThat(drift.got()).isEqualTo("legacy");
	}

	@Test
	void detectsHttpsNotEnforced() {
		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.pages());
		var actual = Optional.of(
				new ActualPages(
						"workflow",
						Optional.empty(),
						false // https_enforced is false → drift
				)
		);
		var group = new PagesDriftGroup(
				desired.pages,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("pages.https_enforced");
		assertThat(drift.wanted()).isEqualTo(true);
		assertThat(drift.got()).isEqualTo(false);
	}

	@Test
	void detectsSourceBranchMismatch() {
		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.legacyPages("main", "/docs"));
		var actual = Optional.of(
				new ActualPages(
						"legacy",
						Optional.of(new ActualPages.Source("gh-pages", "/")),
						true
				)
		);
		var group = new PagesDriftGroup(
				desired.pages,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).hasSize(2);
	}

	@Test
	void noDriftWhenAllMatch() {
		var desired = Desired.repository("owner", "repo")
				.withPages(Desired.legacyPages("main", "/docs"));
		var actual = Optional.of(
				new ActualPages(
						"legacy",
						Optional.of(new ActualPages.Source("main", "/docs")),
						true
				)
		);
		var group = new PagesDriftGroup(
				desired.pages,
				actual,
				null,
				new RepoRef("owner", "repo")
		);

		var items = group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();

		assertThat(items).isEmpty();
	}

}
