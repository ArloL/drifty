package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NativeExecutableIT {

	@Test
	void version() throws IOException, InterruptedException {
		String nativeExecutable = System.getProperty("native.executable");
		String expectedArtifactId = System.getProperty("project.artifactId");
		String expectedVersion = System.getProperty("project.version");
		String expected = expectedArtifactId + " version \"" + expectedVersion
				+ "\"";

		Process process = new ProcessBuilder(
				Path.of(nativeExecutable).toAbsolutePath().toString(),
				"--version"
		).start();
		int exitCode = process.waitFor();
		String output = new String(process.getInputStream().readAllBytes())
				.strip();

		assertThat(exitCode).isZero();
		assertThat(output).isEqualTo(expected);
	}

}
