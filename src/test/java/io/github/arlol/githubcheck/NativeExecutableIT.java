package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NativeExecutableIT {

	private static final Path EXECUTABLE = Path
			.of(System.getProperty("native.executable"));

	@Test
	void version() throws IOException, InterruptedException {
		String expectedArtifactId = System.getProperty("project.artifactId");
		String expectedVersion = System.getProperty("project.version");
		String expected = expectedArtifactId + " version \"" + expectedVersion
				+ "\"";

		Process process = new ProcessBuilder(
				EXECUTABLE.toAbsolutePath().toString(),
				"--version"
		).start();
		int exitCode = process.waitFor();
		String output = new String(process.getInputStream().readAllBytes())
				.strip();

		assertThat(exitCode).isZero();
		assertThat(output).isEqualTo(expected);
	}

	@Test
	void runsWithoutChangingAnything()
			throws IOException, InterruptedException {
		Process process = new ProcessBuilder(
				EXECUTABLE.toAbsolutePath().toString()
		).start();
		int exitCode = process.waitFor();

		assertThat(exitCode).isZero();

		Process gitDiff = new ProcessBuilder("git", "diff", "--exit-code")
				.start();
		int gitExitCode = gitDiff.waitFor();

		assertThat(gitExitCode).isZero();
	}

}
