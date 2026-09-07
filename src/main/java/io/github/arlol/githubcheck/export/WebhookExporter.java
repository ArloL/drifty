package io.github.arlol.githubcheck.export;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A webhook, as the config lines that differ from the schema's defaults.
 * <p>
 * GitHub gives a webhook no name of its own — every hook is literally called
 * "web", per {@code Webhook}'s class comment in {@code config/drifty.pkl} — so
 * the key is synthesized from the url's host and path rather than read off the
 * entity, the same way its url is what matches an entry to a hook once the
 * config is edited by hand.
 */
public final class WebhookExporter {

	private static final String SECRET_NOTE = "the webhook secret is never returned by GitHub; supply it through DRIFTY_GITHUB_SECRETS";

	private WebhookExporter() {
	}

	public static PklNode.Member entry(
			ActualWebhook actual,
			Drifty.Webhook defaults
	) {
		List<PklNode.Member> members = new ArrayList<>(
				Fields.members(
						Fields.required("url", actual.url()),
						Fields.field(
								"contentType",
								actual.contentType(),
								defaults.contentType.toString()
						),
						Fields.field(
								"insecureSsl",
								actual.insecureSsl(),
								defaults.insecureSsl
						),
						Fields.field(
								"active",
								actual.active(),
								defaults.active
						),
						Fields.strings(
								"events",
								actual.events(),
								defaults.events
						),
						Fields.field(
								"secret",
								actual.hasSecret(),
								defaults.secret
						)
				)
		);
		if (actual.hasSecret()) {
			members.add(Fields.note(SECRET_NOTE));
		}
		return new PklNode.Field(key(actual.url()), new PklNode.Obj(members));
	}

	/**
	 * Host plus path, since a bare host would collide for two hooks on the same
	 * endpoint at different paths and the query string carries nothing a config
	 * author would want to see repeated in a key.
	 */
	private static String key(String url) {
		URI uri = URI.create(url);
		String path = uri.getPath();
		return uri.getHost() + (path == null ? "" : path);
	}

}
