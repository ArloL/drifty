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
	void unknownGroupName_failsToEvaluate(@TempDir Path tempDir)
			throws IOException {
		Path schema = Path.of("config/drifty.pkl").toAbsolutePath();
		Path config = tempDir.resolve("drifty.pkl");
		Files.writeString(config, """
				amends "%s"

				repositories {
				  new {
				    owner = "owner"
				    name = "repo"
				    managed { mode = "only"; groups { "not_a_group" } }
				  }
				}
				""".formatted(schema));

		assertThatThrownBy(() -> PklConfigLoader.load(config))
				.hasMessageContaining("not_a_group");
	}

}
