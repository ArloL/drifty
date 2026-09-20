package io.github.arlol.githubcheck.client;

import org.jspecify.annotations.Nullable;

public class GitHubApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public GitHubApiException(@Nullable String message) {
		super(message);
	}

	public GitHubApiException(@Nullable String message, Throwable cause) {
		super(message, cause);
	}

}
