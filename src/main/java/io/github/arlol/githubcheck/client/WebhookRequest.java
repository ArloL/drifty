package io.github.arlol.githubcheck.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the webhook POST and PATCH. {@code name} is only sent on creation,
 * where the repository endpoint requires the literal {@code web}; {@code
 * config.secret} is only sent when the config declares one, since the whole
 * config object replaces what GitHub had.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookRequest(
		String name,
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
			String secret,
			String insecureSsl
	) {
	}

}
