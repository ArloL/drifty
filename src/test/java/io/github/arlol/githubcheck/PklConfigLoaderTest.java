package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.config.RepositoryArgs;

class PklConfigLoaderTest {

	private static final Comparator<RepositoryArgs> BY_OWNER_NAME = Comparator
			.comparing(RepositoryArgs::owner)
			.thenComparing(RepositoryArgs::name);

	@Test
	void pklRepositoriesMatchHardcodedRepositories() throws IOException {
		List<RepositoryArgs> fromPkl = PklConfigLoader
				.load(Path.of("config/ArloL.pkl").toAbsolutePath());
		List<RepositoryArgs> fromJava = GitHubCheck.repositories();

		assertThat(fromPkl.stream().sorted(BY_OWNER_NAME).toList())
				.usingRecursiveComparison()
				.isEqualTo(fromJava.stream().sorted(BY_OWNER_NAME).toList());
	}

}
