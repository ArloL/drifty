package io.github.arlol.githubcheck.client;

import java.util.List;

/**
 * A repository or organization webhook as GitHub returns it. {@code
 * config.secret} is {@code ********} when one is set and absent otherwise;
 * {@code config.insecure_ssl} arrives as the string or number {@code "0"} or
 * {@code "1"}, which Jackson coerces to a String either way.
 */
public record WebhookResponse(
		long id,
		String name,
		boolean active,
		List<String> events,
		Config config,
		String createdAt,
		String updatedAt
) {

	public WebhookResponse {
		events = events == null ? List.of() : List.copyOf(events);
	}

	public record Config(
			String url,
			String contentType,
			String secret,
			String insecureSsl
	) {
	}

}
