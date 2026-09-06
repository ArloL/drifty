package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.arlol.githubcheck.actual.ActualWebhook;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class WebhooksDriftGroupTest {

	private static final String URL = "https://example.com/hook";
	private static final String HOOK_JSON = """
			{
			  "id": 7,
			  "name": "web",
			  "active": true,
			  "events": ["push"],
			  "config": {"url": "https://example.com/hook", "content_type": "form", "insecure_ssl": "0", "secret": "********"},
			  "updated_at": "2024-06-01T00:00:00Z"
			}
			""";

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static ActualWebhook hook(
			String url,
			boolean hasSecret,
			String updatedAt
	) {
		return new ActualWebhook(
				7,
				url,
				"form",
				false,
				true,
				Set.of("push"),
				hasSecret,
				updatedAt
		);
	}

	private WebhooksDriftGroup group(
			Map<String, Drifty.Webhook> desired,
			List<ActualWebhook> actual
	) {
		return group(desired, actual, Map.of(), new DriftyState());
	}

	private WebhooksDriftGroup group(
			Map<String, Drifty.Webhook> desired,
			List<ActualWebhook> actual,
			Map<String, String> secretValues,
			DriftyState state
	) {
		return new WebhooksDriftGroup(
				desired,
				actual,
				secretValues,
				state,
				client,
				new RepoRef("owner", "repo")
		);
	}

	private static List<DriftItem> items(DriftGroup<?> group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
	}

	@Test
	void noDrift_whenHookMatchesByUrl() {
		var group = group(
				Map.of("ci", Desired.webhook(URL)),
				List.of(hook(URL, false, "t"))
		);

		assertThat(items(group)).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.GroupName.WEBHOOKS);
	}

	@Test
	void detectsEveryReturnedField() {
		var wanted = Desired.webhook(URL)
				.withContentType(Drifty.WebhookContentType.JSON)
				.withInsecureSsl(true)
				.withActive(false)
				.withEvents(List.of("push", "pull_request"));

		var items = items(
				group(Map.of("ci", wanted), List.of(hook(URL, false, "t")))
		);

		assertThat(items).extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"webhooks.ci.content_type",
						"webhooks.ci.insecure_ssl",
						"webhooks.ci.active",
						"webhooks.ci.events"
				);
	}

	@Test
	void secretDeclaredButAbsentOnGitHub_isMissing() {
		var items = items(
				group(
						Map.of("ci", Desired.webhook(URL).withSecret(true)),
						List.of(hook(URL, false, "t"))
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class)
				.extracting(DriftItem::path)
				.isEqualTo("webhooks.ci.secret");
	}

	@Test
	void secretOnGitHubButNotDeclared_isAMismatch() {
		var items = items(
				group(
						Map.of("ci", Desired.webhook(URL)),
						List.of(hook(URL, true, "t"))
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.FieldMismatch.class)
				.extracting(DriftItem::path)
				.isEqualTo("webhooks.ci.secret");
	}

	@Test
	void secretWithoutRecord_hasNoBaseline() {
		var items = items(
				group(
						Map.of("ci", Desired.webhook(URL).withSecret(true)),
						List.of(hook(URL, true, "t"))
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SecretMissingBaseline.class);
	}

	@Test
	void secretChangedOutsideDrifty_whenUpdatedAtMoved() {
		var state = new DriftyState();
		state.recordWebhookSecret("repo", "ci", "t1", state.hash("v"));

		var items = items(
				group(
						Map.of("ci", Desired.webhook(URL).withSecret(true)),
						List.of(hook(URL, true, "t2")),
						Map.of("repo-webhook-ci", "v"),
						state
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SecretChanged.class);
	}

	@Test
	void secretValueChanged_whenConfiguredValueHashesDifferently() {
		var state = new DriftyState();
		state.recordWebhookSecret("repo", "ci", "t", state.hash("old"));

		var items = items(
				group(
						Map.of("ci", Desired.webhook(URL).withSecret(true)),
						List.of(hook(URL, true, "t")),
						Map.of("repo-webhook-ci", "new"),
						state
				)
		);

		assertThat(items).singleElement()
				.isInstanceOf(DriftItem.SecretValueChanged.class);
	}

	@Test
	void noDrift_whenRecordMatchesUpdatedAtAndValue() {
		var state = new DriftyState();
		state.recordWebhookSecret("repo", "ci", "t", state.hash("v"));

		var group = group(
				Map.of("ci", Desired.webhook(URL).withSecret(true)),
				List.of(hook(URL, true, "t")),
				Map.of("repo-webhook-ci", "v"),
				state
		);

		assertThat(items(group)).isEmpty();
	}

	@Test
	void duplicateUrlInConfig_isReportedAndNeverFixed() {
		var group = group(
				Map.of("a", Desired.webhook(URL), "b", Desired.webhook(URL)),
				List.of(hook(URL, false, "t"))
		);

		var fixes = group.detect();

		var duplicate = fixes.stream()
				.filter(f -> !f.items().isEmpty())
				.toList();
		assertThat(duplicate).singleElement().satisfies(fix -> {
			assertThat(fix.items()).singleElement()
					.extracting(DriftItem::path)
					.asString()
					.endsWith(".url");
			assertThat(fix.fix().execute().unfixedItems()).singleElement()
					.extracting(FixResult.Unfixed::reason)
					.asString()
					.contains("share a url");
		});
	}

	@Test
	void extraHook_isDeleted() {
		stubFor(
				delete(urlPathEqualTo("/repos/owner/repo/hooks/7"))
						.willReturn(aResponse().withStatus(204))
		);
		var group = group(Map.of(), List.of(hook(URL, false, "t")));

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class)
				.extracting(DriftItem::path)
				.isEqualTo("webhooks." + URL);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(deleteRequestedFor(urlPathEqualTo("/repos/owner/repo/hooks/7")));
	}

	@Test
	void missingHook_isCreatedWithNameWebAndNoSecretField() {
		stubFor(
				post(urlPathEqualTo("/repos/owner/repo/hooks")).willReturn(
						aResponse().withStatus(201).withBody(HOOK_JSON)
				)
		);
		var group = group(
				Map.of("ci", Desired.webhook(URL).withInsecureSsl(true)),
				List.of()
		);

		var fixes = group.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlPathEqualTo("/repos/owner/repo/hooks"))
						.withRequestBody(
								equalToJson(
										"""
												{
												  "name": "web",
												  "config": {"url": "https://example.com/hook", "content_type": "form", "insecure_ssl": "1"},
												  "events": ["push"],
												  "active": true
												}
												"""
								)
						)
		);
	}

	@Test
	void driftedHook_isPatchedWithTheSecretAndRecorded() {
		stubFor(
				patch(urlPathEqualTo("/repos/owner/repo/hooks/7"))
						.willReturn(okJson(HOOK_JSON))
		);
		var state = new DriftyState();
		var group = group(
				Map.of(
						"ci",
						Desired.webhook(URL)
								.withContentType(Drifty.WebhookContentType.JSON)
								.withSecret(true)
				),
				List.of(hook(URL, true, "t")),
				Map.of("repo-webhook-ci", "s3cret"),
				state
		);

		var fixes = group.detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(urlPathEqualTo("/repos/owner/repo/hooks/7"))
						.withRequestBody(
								equalToJson(
										"""
												{
												  "config": {"url": "https://example.com/hook", "content_type": "json", "secret": "s3cret", "insecure_ssl": "0"},
												  "events": ["push"],
												  "active": true
												}
												"""
								)
						)
		);
		var record = state.webhookSecretRecord("repo", "ci");
		assertThat(record.updatedAt()).isEqualTo("2024-06-01T00:00:00Z");
		assertThat(record.valueHash()).isEqualTo(state.hash("s3cret"));
	}

	@Test
	void fixWithoutSecretValue_isReportedUnfixed() {
		var group = group(
				Map.of("ci", Desired.webhook(URL).withSecret(true)),
				List.of(hook(URL, false, "t"))
		);

		var fixes = group.detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("repo-webhook-ci");
	}

}
