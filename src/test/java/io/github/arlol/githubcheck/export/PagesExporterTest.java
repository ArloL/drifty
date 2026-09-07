package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualPages;

class PagesExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static String render(ActualPages actual) {
		return PklWriter.write(PagesExporter.node(actual, DEFAULTS.pages()));
	}

	@Test
	void aWorkflowBuildWithNoSourceExportsAnEmptyObject() {
		ActualPages actual = new ActualPages(
				"workflow",
				Optional.empty(),
				true
		);
		assertThat(render(actual)).isEqualTo("");
	}

	@Test
	void aLegacyBuildTypeIsEmitted() {
		ActualPages actual = new ActualPages("legacy", Optional.empty(), true);
		assertThat(render(actual)).isEqualTo("""
				buildType = "legacy"
				""");
	}

	@Test
	void aPresentSourceIsEmittedAsBranchAndPath() {
		ActualPages actual = new ActualPages(
				"legacy",
				Optional.of(new ActualPages.Source("gh-pages", "/docs")),
				true
		);
		assertThat(render(actual)).isEqualTo("""
				buildType = "legacy"
				sourceBranch = "gh-pages"
				sourcePath = "/docs"
				""");
	}

	@Test
	void anAbsentSourceOmitsBothSourceFields() {
		// Distinguishes an absent Source from one whose fields happen to
		// match the schema default (there is none for either — both are
		// null) — an implementation that unwraps Optional.get() rather than
		// guarding presence would throw here instead of reaching the
		// assertion.
		ActualPages actual = new ActualPages(
				"workflow",
				Optional.empty(),
				true
		);
		assertThat(render(actual)).doesNotContain("sourceBranch")
				.doesNotContain("sourcePath");
	}

	/**
	 * A pre-2018 site GitHub reports no build type for at all: writing
	 * {@code null} into a non-nullable schema field would produce a file the
	 * schema refuses to load, so the field is left out rather than forcing a
	 * value the schema cannot hold.
	 */
	@Test
	void aNullBuildTypeIsOmittedRatherThanWrittenAsNull() {
		ActualPages actual = new ActualPages(null, Optional.empty(), true);
		assertThat(render(actual)).isEqualTo("");
	}

	@Test
	void httpsEnforcedIsNeverEmitted() {
		ActualPages enforced = new ActualPages(
				"legacy",
				Optional.of(new ActualPages.Source("main", "/")),
				true
		);
		ActualPages notEnforced = new ActualPages(
				"legacy",
				Optional.of(new ActualPages.Source("main", "/")),
				false
		);
		assertThat(render(enforced)).isEqualTo(render(notEnforced));
	}

}
