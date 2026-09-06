package io.github.arlol.githubcheck.actual;

/**
 * A repository or environment Actions variable: plaintext, so the value is
 * compared.
 */
public record ActualVariable(
		String name,
		String value
) {
}
