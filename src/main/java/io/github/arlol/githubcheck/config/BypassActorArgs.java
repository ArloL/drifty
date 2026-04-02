package io.github.arlol.githubcheck.config;

import io.github.arlol.githubcheck.client.RulesetDetailsResponse;

public record BypassActorArgs(
		Long actorId,
		RulesetDetailsResponse.BypassActor.ActorType actorType,
		RulesetDetailsResponse.BypassActor.BypassMode bypassMode
) {
}
