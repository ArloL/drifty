package io.github.arlol.githubcheck.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.config.PagesArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;

class PagesDriftGroupTest {

	@Test
	void noDriftWhenPagesNotDesired() {
		var desired = RepositoryArgs.create("owner", "repo").build();
		var actual = Optional.of(
				new PagesResponse(
						null,
						PagesResponse.Status.BUILT,
						null,
						false,
						null,
						PagesBuildType.WORKFLOW,
						null,
						true,
						null,
						null,
						null,
						true
				)
		);
		var group = new PagesDriftGroup(desired, actual, null, "owner", "repo");

		var items = group.detect();

		assertThat(items).isEmpty();
	}

	@Test
	void detectsMissingPages() {
		var desired = RepositoryArgs.create("owner", "repo").pages().build();
		Optional<PagesResponse> actual = Optional.empty();
		var group = new PagesDriftGroup(desired, actual, null, "owner", "repo");

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(items.getFirst().message()).isEqualTo("pages: missing");
	}

	@Test
	void detectsBuildTypeMismatch() {
		var desired = RepositoryArgs.create("owner", "repo").pages().build(); // wants
																			  // WORKFLOW
		var actual = Optional.of(
				new PagesResponse(
						null,
						PagesResponse.Status.BUILT,
						null,
						false,
						null,
						PagesBuildType.LEGACY,
						new PagesResponse.Source("gh-pages", "/"),
						true,
						null,
						null,
						null,
						true
				)
		);
		var group = new PagesDriftGroup(desired, actual, null, "owner", "repo");

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("build_type");
		assertThat(drift.wanted()).isEqualTo("workflow");
		assertThat(drift.got()).isEqualTo("legacy");
	}

	@Test
	void detectsHttpsNotEnforced() {
		var desired = RepositoryArgs.create("owner", "repo").pages().build();
		var actual = Optional.of(
				new PagesResponse(
						null,
						PagesResponse.Status.BUILT,
						null,
						false,
						null,
						PagesBuildType.WORKFLOW,
						null,
						true,
						null,
						null,
						null,
						false // https_enforced is false → drift
				)
		);
		var group = new PagesDriftGroup(desired, actual, null, "owner", "repo");

		var items = group.detect();

		assertThat(items).hasSize(1);
		assertThat(items.getFirst())
				.isInstanceOf(DriftItem.FieldMismatch.class);
		var drift = (DriftItem.FieldMismatch) items.getFirst();
		assertThat(drift.path()).isEqualTo("https_enforced");
		assertThat(drift.wanted()).isEqualTo(true);
		assertThat(drift.got()).isEqualTo(false);
	}

	@Test
	void detectsSourceBranchMismatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.pages(PagesArgs.legacy("main", "/docs"))
				.build();
		var actual = Optional.of(
				new PagesResponse(
						null,
						PagesResponse.Status.BUILT,
						null,
						false,
						null,
						PagesBuildType.LEGACY,
						new PagesResponse.Source("gh-pages", "/"),
						true,
						null,
						null,
						null,
						true
				)
		);
		var group = new PagesDriftGroup(desired, actual, null, "owner", "repo");

		var items = group.detect();

		assertThat(items).hasSize(2);
	}

	@Test
	void noDriftWhenAllMatch() {
		var desired = RepositoryArgs.create("owner", "repo")
				.pages(PagesArgs.legacy("main", "/docs"))
				.build();
		var actual = Optional.of(
				new PagesResponse(
						null,
						PagesResponse.Status.BUILT,
						null,
						false,
						null,
						PagesBuildType.LEGACY,
						new PagesResponse.Source("main", "/docs"),
						true,
						null,
						null,
						null,
						true
				)
		);
		var group = new PagesDriftGroup(desired, actual, null, "owner", "repo");

		var items = group.detect();

		assertThat(items).isEmpty();
	}

}
