package io.github.arlol.githubcheck.client;

import java.util.List;

public record ReplaceTopicsRequest(
		List<String> names
) {

	public ReplaceTopicsRequest {
		names = List.copyOf(names);
	}

}
