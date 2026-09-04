package io.github.arlol.githubcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
		assertSelfTestPasses();
	}

	/**
	 * Loading a config is the other reflection-heavy path in the shipped
	 * binary, and it broke the same way JNA did: Pkl's mapper instantiates the
	 * map type it is asked for reflectively, so a type without native-image
	 * metadata ends the run with "no conversion was found" — after every JVM
	 * test had passed. Running the real binary against the example config is
	 * what makes that a build failure instead of a user's first command.
	 */
	@Test
	void selfTestWithConfig() throws IOException, InterruptedException {
		assertSelfTestPasses("--config", "config/example.pkl");
	}

	private static void assertSelfTestPasses(String... extraArgs)
			throws IOException, InterruptedException {
		String nativeExecutable = System.getProperty("native.executable");

		var command = new ArrayList<String>();
		command.add(Path.of(nativeExecutable).toAbsolutePath().toString());
		command.add("--self-test");
		command.addAll(List.of(extraArgs));

		Process process = new ProcessBuilder(command).redirectErrorStream(true)
				.start();
		int exitCode = process.waitFor();
		String output = new String(process.getInputStream().readAllBytes())
				.strip();

		assertEquals(
				0,
				exitCode,
				() -> "native self-test failed: " + command + "\n" + output
		);
		assertEquals("self-test OK", output);
	}

}
