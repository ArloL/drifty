package io.github.arlol.githubcheck.drift;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
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

import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.testsupport.Desired;

@WireMockTest
class OrgCodeSecurityConfigurationsDriftGroupTest {

	private static final String BASE = "/orgs/my-org/code-security/configurations";

	private GitHubClient client;

	@BeforeEach
	void setUp(WireMockRuntimeInfo wm) {
		client = new GitHubClient(wm.getHttpBaseUrl(), "test-token");
	}

	/**
	 * GitHub's POST defaults, as {@code Desired.codeSecurityConfiguration()}.
	 */
	private static ActualCodeSecurityConfiguration defaults(
			String name,
			String defaultForNewRepos,
			Set<String> repositories
	) {
		var settings = new java.util.HashMap<String, String>();
		for (String disabled : List.of(
				"advanced_security",
				"dependency_graph_autosubmit_action",
				"dependabot_alerts",
				"dependabot_security_updates",
				"dependabot_delegated_alert_dismissal",
				"code_scanning_default_setup",
				"secret_scanning",
				"secret_scanning_push_protection",
				"secret_scanning_delegated_bypass",
				"secret_scanning_validity_checks",
				"secret_scanning_non_provider_patterns",
				"secret_scanning_generic_secrets",
				"private_vulnerability_reporting"
		)) {
			settings.put(disabled, "disabled");
		}
		settings.put("dependency_graph", "enabled");
		settings.put("code_scanning_delegated_alert_dismissal", "not_set");
		settings.put("secret_scanning_delegated_alert_dismissal", "not_set");
		return new ActualCodeSecurityConfiguration(
				7,
				name,
				"",
				settings,
				"enforced",
				defaultForNewRepos,
				repositories
		);
	}

	private OrgCodeSecurityConfigurationsDriftGroup group(
			Map<String, Drifty.CodeSecurityConfiguration> desired,
			List<ActualCodeSecurityConfiguration> actual
	) {
		return new OrgCodeSecurityConfigurationsDriftGroup(
				desired,
				actual,
				Map.of("one", 10L, "two", 11L),
				client,
				"my-org"
		);
	}

	private static List<DriftItem> items(DriftGroup<?> group) {
		return group.detect()
				.stream()
				.flatMap(f -> f.items().stream())
				.toList();
	}

	@Test
	void noDrift_whenAConfigurationWithOnlyANameMatchesGitHubsDefaults() {
		var group = group(
				Map.of("baseline", Desired.codeSecurityConfiguration()),
				List.of(defaults("baseline", "none", Set.of()))
		);

		assertThat(items(group)).isEmpty();
		assertThat(group.name()).isEqualTo(
				Drifty.OrgGroupName.ORG_CODE_SECURITY_CONFIGURATIONS
		);
	}

	@Test
	void settingsDefaultsAndRepositoriesAreSeparateFixes() {
		stubFor(patch(urlPathEqualTo(BASE + "/7")).willReturn(okJson()));
		stubFor(put(urlPathEqualTo(BASE + "/7/defaults")).willReturn(okJson()));
		stubFor(
				post(urlPathEqualTo(BASE + "/7/attach"))
						.willReturn(aResponse().withStatus(202))
		);
		var wanted = Desired.codeSecurityConfiguration()
				.withSecretScanning(Drifty.SecuritySetting.ENABLED)
				.withEnforcement(Drifty.SecurityEnforcement.UNENFORCED)
				.withDefaultForNewRepos(Drifty.DefaultForNewRepos.ALL)
				.withRepositories(List.of("one", "two"));

		var fixes = group(
				Map.of("baseline", wanted),
				List.of(defaults("baseline", "none", Set.of("one", "stray")))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_code_security_configurations.baseline.secret_scanning",
						"org_code_security_configurations.baseline.enforcement",
						"org_code_security_configurations.baseline.default_for_new_repos",
						"org_code_security_configurations.baseline.repositories",
						"org_code_security_configurations.baseline.repositories.stray"
				);
		var unfixed = fixes.stream()
				.filter(f -> !f.items().isEmpty())
				.flatMap(f -> f.fix().execute().unfixedItems().stream())
				.toList();
		assertThat(unfixed).singleElement()
				.extracting(u -> u.item().path())
				.isEqualTo(
						"org_code_security_configurations.baseline.repositories.stray"
				);
		verify(
				patchRequestedFor(urlPathEqualTo(BASE + "/7")).withRequestBody(
						equalToJson(
								"""
										{"name": "baseline", "secret_scanning": "enabled", "enforcement": "unenforced"}
										""",
								true,
								true
						)
				)
		);
		verify(
				putRequestedFor(urlPathEqualTo(BASE + "/7/defaults"))
						.withRequestBody(
								equalToJson(
										"{\"default_for_new_repos\": \"all\"}"
								)
						)
		);
		verify(
				postRequestedFor(
						urlPathEqualTo(BASE + "/7/attach")
				).withRequestBody(equalToJson("""
						{"scope": "selected", "selected_repository_ids": [11]}
						"""))
		);
	}

	@Test
	void missingConfiguration_isCreatedThenDefaultedAndAttached() {
		stubFor(
				post(urlPathEqualTo(BASE)).willReturn(
						aResponse().withStatus(201)
								.withBody("{\"id\": 9, \"name\": \"new\"}")
				)
		);
		stubFor(put(urlPathEqualTo(BASE + "/9/defaults")).willReturn(okJson()));
		stubFor(
				post(urlPathEqualTo(BASE + "/9/attach"))
						.willReturn(aResponse().withStatus(202))
		);
		var wanted = Desired.codeSecurityConfiguration()
				.withDescription("baseline")
				.withDefaultForNewRepos(Drifty.DefaultForNewRepos.PUBLIC)
				.withRepositories(List.of("one"));

		var fixes = group(Map.of("new", wanted), List.of()).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionMissing.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				postRequestedFor(urlPathEqualTo(BASE)).withRequestBody(
						equalToJson(
								"""
										{"name": "new", "description": "baseline",
										 "advanced_security": "disabled", "dependency_graph": "enabled",
										 "dependency_graph_autosubmit_action": "disabled",
										 "dependabot_alerts": "disabled", "dependabot_security_updates": "disabled",
										 "dependabot_delegated_alert_dismissal": "disabled",
										 "code_scanning_default_setup": "disabled",
										 "code_scanning_delegated_alert_dismissal": "not_set",
										 "secret_scanning": "disabled", "secret_scanning_push_protection": "disabled",
										 "secret_scanning_delegated_bypass": "disabled",
										 "secret_scanning_validity_checks": "disabled",
										 "secret_scanning_non_provider_patterns": "disabled",
										 "secret_scanning_generic_secrets": "disabled",
										 "secret_scanning_delegated_alert_dismissal": "not_set",
										 "private_vulnerability_reporting": "disabled",
										 "enforcement": "enforced"}
										"""
						)
				)
		);
		verify(putRequestedFor(urlPathEqualTo(BASE + "/9/defaults")));
		verify(
				postRequestedFor(
						urlPathEqualTo(BASE + "/9/attach")
				).withRequestBody(equalToJson("""
						{"scope": "selected", "selected_repository_ids": [10]}
						"""))
		);
	}

	@Test
	void unknownRepository_isReportedUnfixed() {
		var wanted = Desired.codeSecurityConfiguration()
				.withRepositories(List.of("nope"));

		var fixes = group(
				Map.of("baseline", wanted),
				List.of(defaults("baseline", "none", Set.of()))
		).detect();

		var attach = fixes.stream()
				.filter(f -> !f.items().isEmpty())
				.findFirst()
				.orElseThrow();
		assertThat(attach.fix().execute().unfixedItems()).singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("no repository nope");
	}

	@Test
	void extraConfiguration_isReportedAndNeverDeleted() {
		var fixes = group(
				Map.of(),
				List.of(defaults("stray", "none", Set.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.singleElement()
				.isInstanceOf(DriftItem.SectionExtra.class);
		assertThat(fixes.getFirst().fix().execute().unfixedItems())
				.singleElement()
				.extracting(FixResult.Unfixed::reason)
				.asString()
				.contains("does not delete code security configurations");
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson() {
		return aResponse().withStatus(200).withBody("{}");
	}

}
