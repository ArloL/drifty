package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.arlol.githubcheck.pkl.Drifty;

class PklConfigLoaderTest {

	@Test
	void loadsArloLConfig() throws IOException {
		List<Drifty.Repository> repos = PklConfigLoader
				.load(Path.of("config/ArloL.pkl").toAbsolutePath());

		assertThat(repos).isNotEmpty();
		assertThat(repos)
				.allSatisfy(repo -> assertThat(repo.owner).isEqualTo("ArloL"));
		assertThat(repos)
				.anySatisfy(repo -> assertThat(repo.name).isEqualTo("drifty"));
	}

	@Test
	void managedDefaultsToEverything() throws IOException {
		List<Drifty.Repository> repos = PklConfigLoader
				.load(Path.of("config/ArloL.pkl").toAbsolutePath());

		assertThat(repos).allSatisfy(
				repo -> assertThat(repo.managed.mode)
						.isEqualTo(Drifty.ManageMode.ALL_EXCEPT)
		);
		assertThat(repos)
				.allSatisfy(repo -> assertThat(repo.managed.groups).isEmpty());
	}

	@Test
	void organisationSuppliesTheOwnerOfEveryRepository(@TempDir Path tempDir)
			throws IOException {
		Path config = write(tempDir, """
				organisation = "acme"

				repositories {
				  new { name = "repo" }
				}
				""");

		List<Drifty.Repository> repos = PklConfigLoader.load(config);

		assertThat(repos).singleElement()
				.satisfies(repo -> assertThat(repo.owner).isEqualTo("acme"));
	}

	@Test
	void repositoryOwnerOverridesTheOrganisation(@TempDir Path tempDir)
			throws IOException {
		Path config = write(tempDir, """
				organisation = "acme"

				repositories {
				  new { name = "own" }
				  new { owner = "other"; name = "foreign" }
				}
				""");

		List<Drifty.Repository> repos = PklConfigLoader.load(config);

		assertThat(repos).extracting(repo -> repo.owner + "/" + repo.name)
				.containsExactly("acme/own", "other/foreign");
	}

	@Test
	void missingOrganisation_failsToEvaluate(@TempDir Path tempDir)
			throws IOException {
		Path config = write(tempDir, """
				repositories {
				  new { owner = "other"; name = "foreign" }
				}
				""");

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("organisation");
	}

	@Test
	void unknownGroupName_failsToEvaluate(@TempDir Path tempDir)
			throws IOException {
		Path config = write(tempDir, """
				organisation = "owner"

				repositories {
				  new {
				    name = "repo"
				    managed { mode = "only"; groups { "not_a_group" } }
				  }
				}
				""");

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("not_a_group");
	}

	/**
	 * Writes a config amending the schema by file: URI, not by plain path: on
	 * Windows an absolute path is "D:\\a\\drifty\\config\\drifty.pkl", and Pkl
	 * rejects "\\a" as an invalid escape before it ever reaches what is under
	 * test.
	 */
	private static Path write(Path tempDir, String body) throws IOException {
		String schema = Path.of("config/drifty.pkl")
				.toAbsolutePath()
				.toUri()
				.toString();
		Path config = tempDir.resolve("drifty.pkl");
		Files.writeString(
				config,
				"amends \"%s\"%n%n%s".formatted(schema, body)
		);
		return config;
	}

}
