package io.github.arlol.githubcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

		assertEquals(0, exitCode);
		assertEquals(expected, output);
	}

	/**
	 * Exercises the real libsodium/JNA path inside the production native image.
	 *
	 * <p>
	 * lazysodium binds libsodium with JNA direct mapping
	 * ({@code Native.register}), which the reachability-metadata repository's
	 * JNA config does not cover, so the entries are self-supplied here. Without
	 * them {@code new SodiumJava()} dies with
	 * {@code NoSuchMethodException: com.sun.jna.Structure$FFIType.<init>()}.
	 *
	 * <p>
	 * {@code SecretsTest} does not catch that: the native <em>test</em> image
	 * sees the test-scoped metadata too. This runs the shipped binary, built
	 * from production scope alone, so the regression fails the build here
	 * instead of at a user's terminal. See {@code FOLLOWUPS.md}.
	 */
	@Test
	void selfTest() throws IOException, InterruptedException {
		String nativeExecutable = System.getProperty("native.executable");

		Process process = new ProcessBuilder(
				Path.of(nativeExecutable).toAbsolutePath().toString(),
				"--self-test"
		).redirectErrorStream(true).start();
		int exitCode = process.waitFor();
		String output = new String(process.getInputStream().readAllBytes())
				.strip();

		assertEquals(
				0,
				exitCode,
				() -> "native crypto self-test failed:\n" + output
		);
		assertEquals("self-test OK", output);
	}

}
