package io.github.arlol.githubcheck.client;

public record SimpleUser(
		String login,
		Long id,
		String nodeId,
		String avatarUrl,
		String gravatarId, // nullable
		String url,
		String htmlUrl,
		String type,
		Boolean siteAdmin,
		String name, // nullable, optional
		String email, // nullable, optional
		String userViewType // optional
) {
}
