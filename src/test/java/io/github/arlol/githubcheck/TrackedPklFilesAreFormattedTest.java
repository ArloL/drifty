package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.arlol.githubcheck.testsupport.PklFormat;

/**
 * Holds the repository's own Pkl files to {@code pkl format}, the rule
 * {@code main.yaml}'s {@code pkl-format} job enforces with the real binary.
 * <p>
 * Running it here too is not redundant. The job runs the {@code pkl} CLI and
 * this runs {@code pkl-formatter}, and they are the same release only because
 * {@code pom.xml}'s {@code pkl.version} is the one pin both resolve — so these
 * four files are where a library that stopped agreeing with the command would
 * show up, rather than in an adopter's export. It also means {@code ./mvnw
 * verify} answers the question without a {@code pkl} on the developer's PATH.
 */
class TrackedPklFilesAreFormattedTest {

	/**
	 * What {@code git ls-files '*.pkl'} lists. Named rather than globbed: a
	 * walk that silently matched nothing would pass, and the count below is
	 * what says a new file was added without being listed here.
	 */
	private static final List<Path> TRACKED = Stream
			.of(
					"config/drifty.pkl",
					"config/example.pkl",
					"src/main/resources/export-defaults.pkl",
					"src/test/resources/desired-defaults.pkl"
			)
			.map(Path::of)
			.toList();

	@ParameterizedTest(name = "{0}")
	@MethodSource("tracked")
	void isWhatPklFormatWrites(Path file) throws IOException {
		assertThat(file).exists();
		PklFormat.assertFormatted(file);
	}

	static List<Path> tracked() throws IOException {
		try (Stream<Path> walk = Files.walk(Path.of("."))) {
			assertThat(
					walk.filter(path -> path.toString().endsWith(".pkl"))
							.filter(path -> !path.startsWith("./target"))
							.count()
			).as(
					"a tracked .pkl file is missing from TRACKED — the"
							+ " pkl-format job would still catch it,"
							+ " but not until CI"
			).isEqualTo(TRACKED.size());
		}
		return TRACKED;
	}

}
