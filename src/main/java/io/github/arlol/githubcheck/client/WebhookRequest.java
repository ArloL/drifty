package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.jspecify.annotations.Nullable;

/**
 * Body of the webhook POST and PATCH. {@code name} is only sent on creation,
 * where the repository endpoint requires the literal {@code web}; {@code
 * config.secret} is only sent when the config declares one, since the whole
 * config object replaces what GitHub had.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@GitHubEndpoint(
		request = { "POST /repos/{owner}/{repo}/hooks",
				"PATCH /repos/{owner}/{repo}/hooks/{hook_id}",
				"POST /orgs/{org}/hooks", "PATCH /orgs/{org}/hooks/{hook_id}" },
		unmanaged = {
				"add_events — WebhookReconciler sends the whole events list, so the incremental forms would be a second way to say the same thing",
				"remove_events — as above",
				"config.username — webhook basic auth is a credential, and drifty does not write credentials GitHub will not let it read back to compare",
				"config.password — as above" },
		undocumented = {
				"name — GitHub requires it as web on the create, which is why the record carries it; the PATCH schema omits it" }
)
public record WebhookRequest(
		@Nullable String name,
		Config config,
		List<String> events,
		boolean active
) {

	public WebhookRequest {
		events = List.copyOf(events);
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Config(
			String url,
			String contentType,
			@Nullable String secret,
			String insecureSsl
	) {
	}

}
