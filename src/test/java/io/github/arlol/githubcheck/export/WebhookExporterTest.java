package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.actual.ActualWebhook;

class WebhookExporterTest {

	private static final SchemaDefaults DEFAULTS = SchemaDefaults
			.of(Path.of("config/drifty.pkl").toAbsolutePath().toString());

	private static ActualWebhook webhook(String url) {
		return new ActualWebhook(
				1L,
				url,
				"form",
				false,
				true,
				Set.of("push"),
				false,
				"2026-01-01T00:00:00Z"
		);
	}

	/**
	 * {@code url} has no schema default and is always written, so "at GitHub's
	 * defaults" means only {@code url} appears rather than nothing at all.
	 */
	@Test
	void aWebhookAtGitHubsDefaultsExportsOnlyItsRequiredUrl() {
		var actual = webhook("https://ci.example.com/hooks/build");

		var field = (PklNode.Field) WebhookExporter
				.entry(actual, DEFAULTS.webhook());

		assertThat(field.name()).isEqualTo("ci.example.com/hooks/build");
		assertThat(PklWriter.write(field.value())).isEqualTo("""
				url = "https://ci.example.com/hooks/build"
				""");
	}

	@Test
	void activeDifferingFromTheDefaultIsEmitted() {
		var base = webhook("https://ci.example.com/hooks/build");
		var actual = new ActualWebhook(
				base.id(),
				base.url(),
				base.contentType(),
				base.insecureSsl(),
				false,
				base.events(),
				base.hasSecret(),
				base.updatedAt()
		);

		var field = (PklNode.Field) WebhookExporter
				.entry(actual, DEFAULTS.webhook());

		assertThat(PklWriter.write(field.value())).isEqualTo("""
				url = "https://ci.example.com/hooks/build"
				active = false
				""");
	}

	@Test
	void aSecretGetsAFieldAndANoteRatherThanAnInventedValue() {
		var base = webhook("https://ci.example.com/hooks/build");
		var actual = new ActualWebhook(
				base.id(),
				base.url(),
				base.contentType(),
				base.insecureSsl(),
				base.active(),
				base.events(),
				true,
				base.updatedAt()
		);

		var field = (PklNode.Field) WebhookExporter
				.entry(actual, DEFAULTS.webhook());

		assertThat(PklWriter.write(field.value())).isEqualTo(
				"""
						url = "https://ci.example.com/hooks/build"
						secret = true
						// the webhook secret is never returned by GitHub; supply it through
						// DRIFTY_GITHUB_SECRETS
						"""
		);
	}

}
