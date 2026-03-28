package io.github.arlol.githubcheck.client;

public record License(
		String key,
		String name,
		String url, // nullable
		String spdxId, // nullable
		String nodeId,
		String htmlUrl // optional
) {
}
