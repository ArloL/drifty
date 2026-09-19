package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pkl.core.http.HttpClient;

import io.github.arlol.githubcheck.export.SchemaDefaults;

class BundledSchemaTest {

	/**
	 * {@link HttpClient#dummyClient()} throws on any request, so a config that
	 * loads through it is a config that reached the schema without the network.
	 */
	@Test
	void aConfigAmendingTheMainSchemaNeedsNoNetwork(@TempDir Path dir)
			throws Exception {
		Path config = dir.resolve("drifty.pkl");
		Files.writeString(config, """
				amends "%s"

				users {
				  ["ArloL"] { repositories { new { name = "drifty" } } }
				}
				""".formatted(BundledSchema.MAIN_SCHEMA_URI));

		DriftyConfig loaded;
		try (var evaluator = BundledSchema.evaluatorBuilder()
				.setHttpClient(HttpClient.dummyClient())
				.build()) {
			loaded = PklConfigLoader.load(config, evaluator);
		}

		assertThat(loaded.users()).containsOnlyKeys("ArloL");
	}

	/**
	 * {@code --export} evaluates the schema a second time, for the defaults it
	 * diffs against.
	 */
	@Test
	void theExportDefaultsNeedNoNetworkEither() {
		assertThat(
				SchemaDefaults.of(BundledSchema.MAIN_SCHEMA_URI)
						.repository().archived
		).isFalse();
	}

	/**
	 * The copy the binary answers with has to be the file the repository ships,
	 * or the generated {@code Drifty} records and the schema a config resolves
	 * to are two different things.
	 */
	@Test
	void theBundledSchemaIsTheFileTheRepositoryShips() throws Exception {
		assertThat(BundledSchema.source()).isEqualTo(
				Files.readString(Path.of("config/drifty.pkl"))
						.replace("\r\n", "\n")
		);
	}

	@Test
	void anyOtherModuleUriIsLeftToPkl() throws Exception {
		assertThat(
				BundledSchema.moduleKeyFactory()
						.create(
								URI.create(
										"https://raw.githubusercontent.com/ArloL/drifty/refs/tags/v1/config/drifty.pkl"
								)
						)
		).isEmpty();
	}

}
