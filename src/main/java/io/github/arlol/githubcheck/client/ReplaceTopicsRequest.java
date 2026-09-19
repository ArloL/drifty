package io.github.arlol.githubcheck.client;

import java.util.List;

@GitHubEndpoint(request = "PUT /repos/{owner}/{repo}/topics")
public record ReplaceTopicsRequest(
		List<String> names
) {

	public ReplaceTopicsRequest {
		names = List.copyOf(names);
	}

}
