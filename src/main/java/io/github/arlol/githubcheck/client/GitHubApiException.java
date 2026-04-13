package io.github.arlol.githubcheck.client;

public class GitHubApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public GitHubApiException(String message) {
		super(message);
	}

	public GitHubApiException(String message, Throwable cause) {
		super(message, cause);
	}

}
