package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;
import io.github.arlol.githubcheck.testsupport.Desired;

/**
 * The comparison is {@link WebhookReconciler}'s and is covered by
 * {@link WebhooksDriftGroupTest}; this checks the organization scope: the
 * endpoints, the secret key and the state record.
 */
@WireMockTest
class OrgWebhooksDriftGroupTest {

	private static final String URL = "https://example.com/hook";

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	private static ActualWebhook hook(boolean hasSecret, String updatedAt) {
		return new ActualWebhook(
				9,
				URL,
				"form",
				false,
				true,
				Set.of("push"),
				hasSecret,
				updatedAt
		);
	}

	private OrgWebhooksDriftGroup group(
			Map<String, Drifty.Webhook> desired,
			List<ActualWebhook> actual,
			Map<String, String> secretValues,
			DriftyState state
	) {
		return new OrgWebhooksDriftGroup(
				desired,
				actual,
				secretValues,
				state,
				client,
				"my-org"
		);
	}

	@Test
	void noDrift_whenHookMatches() {
		var group = group(
				Map.of("audit", Desired.webhook(URL)),
				List.of(hook(false, "t")),
				Map.of(),
				new DriftyState()
		);

		assertThat(group.detect()).flatExtracting(DriftFix::items).isEmpty();
		assertThat(group.name()).isEqualTo(Drifty.OrgGroupName.ORG_WEBHOOKS);
	}

	@Test
	void secretIsReadFromTheOrgRecordAndKey() {
		var state = new DriftyState();
		state.recordOrgWebhookSecret("my-org", "audit", "t", state.hash("v"));

		var matching = group(
				Map.of("audit", Desired.webhook(URL).withSecret(true)),
				List.of(hook(true, "t")),
				Map.of("org-my-org-webhook-audit", "v"),
				state
		);
		assertThat(matching.detect()).flatExtracting(DriftFix::items).isEmpty();

		var rotated = group(
				Map.of("audit", Desired.webhook(URL).withSecret(true)),
				List.of(hook(true, "t")),
				Map.of("org-my-org-webhook-audit", "new"),
				state
		);
		assertThat(rotated.detect()).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SecretValueChanged.class)
				.extracting(DriftItem::path)
				.isEqualTo("org_webhooks.audit.secret");
	}

	@Test
	void missingHook_isCreatedOnTheOrgEndpointAndRecorded() {
		stubFor(
				post(urlPathEqualTo("/orgs/my-org/hooks")).willReturn(
						aResponse().withStatus(201)
								.withBody(
										"""
												{
												  "id": 9,
												  "name": "web",
												  "active": true,
												  "events": ["push"],
												  "config": {"url": "https://example.com/hook", "content_type": "form", "insecure_ssl": "0", "secret": "********"},
												  "updated_at": "2024-06-01T00:00:00Z"
												}
												"""
								)
				)
		);
		var state = new DriftyState();
		var group = group(
				Map.of("audit", Desired.webhook(URL).withSecret(true)),
				List.of(),
				Map.of("org-my-org-webhook-audit", "s3cret"),
				state
		);

		var fixes = group.detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlPathEqualTo("/orgs/my-org/hooks"))
						.withRequestBody(
								equalToJson(
										"""
												{
												  "name": "web",
												  "config": {"url": "https://example.com/hook", "content_type": "form", "secret": "s3cret", "insecure_ssl": "0"},
												  "events": ["push"],
												  "active": true
												}
												"""
								)
						)
		);
		assertThat(state.orgWebhookSecretRecord("my-org", "audit").updatedAt())
				.isEqualTo("2024-06-01T00:00:00Z");
	}

	@Test
	void extraHook_isDeletedOnTheOrgEndpoint() {
		stubFor(
				delete(urlPathEqualTo("/orgs/my-org/hooks/9"))
						.willReturn(aResponse().withStatus(204))
		);
		var group = group(
				Map.of(),
				List.of(hook(false, "t")),
				Map.of(),
				new DriftyState()
		);

		var fixes = group.detect();

		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(deleteRequestedFor(urlPathEqualTo("/orgs/my-org/hooks/9")));
	}

}
