package io.github.arlol.githubcheck.client;

/**
 * GitHub refused a read because the repository cannot have the feature — not
 * because the token may not see it. The message is GitHub's own, which says
 * what is missing.
 * <p>
 * A subtype rather than a status code check at the caller because a 403 is
 * both: {@code GET .../code-scanning/default-setup} answers 403 to a token
 * without the scope and to a private repository on a plan without code
 * scanning, and only the second says nothing about the config.
 */
public class FeatureUnavailableException extends GitHubApiException {

	private static final long serialVersionUID = 1L;

	public FeatureUnavailableException(String message) {
		super(message);
	}

}
