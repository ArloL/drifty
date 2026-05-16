package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.config.RepositoryArgs;

class PklConfigLoaderTest {

	@Test
	void loadsArloLConfig() throws IOException {
		List<RepositoryArgs> repos = PklConfigLoader
				.load(Path.of("config/ArloL.pkl").toAbsolutePath());

		assertThat(repos).isNotEmpty();
		assertThat(repos).allSatisfy(
				repo -> assertThat(repo.owner()).isEqualTo("ArloL")
		);
		assertThat(repos).anySatisfy(
				repo -> assertThat(repo.name()).isEqualTo("drifty")
		);
	}

}
