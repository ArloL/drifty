package io.github.arlol.githubcheck.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pkl.formatter.Formatter;

/**
 * Holds a file to what {@code pkl format} would write, using the formatter that
 * command runs rather than a description of it.
 * <p>
 * {@code PklWriterTest} states the two shapes drifty's writer reproduces — an
 * empty body collapsed onto the line that opens it, and a scalar assignment
 * past {@code LINE_WIDTH} moved to its own line — and it states them as drifty
 * understands them. That is a model of the formatter, so it agrees with itself
 * whether or not the formatter still works that way, and the only thing that
 * ever disagreed was an adopter's CI: issue #138 was the export failing
 * {@code pkl format --diff-name-only} on its first run against a real
 * repository.
 * <p>
 * Checking against the real formatter closes that, and it closes the version of
 * it that has not happened yet — a pkl release changing a rule drifty's model
 * does not know about. {@code pkl.version} in {@code pom.xml} is what keeps
 * this formatter and the binary {@code main.yaml} downloads the same release; a
 * check against a different version would be a third opinion rather than the
 * authority.
 */
public final class PklFormat {

	private PklFormat() {
	}

	public static void assertFormatted(Path file) throws IOException {
		assertFormatted(Files.readString(file), file.toString());
	}

	public static void assertFormatted(String source, String name) {
		assertThat(source)
				.as(
						"%s is not what `pkl format` writes. An adopter commits"
								+ " this file and runs `pkl format"
								+ " --diff-name-only` over it in CI, so drifty"
								+ " has to write the formatter's output and not"
								+ " merely valid Pkl — see PklWriter.",
						name
				)
				.isEqualTo(new Formatter().format(source));
	}

}
