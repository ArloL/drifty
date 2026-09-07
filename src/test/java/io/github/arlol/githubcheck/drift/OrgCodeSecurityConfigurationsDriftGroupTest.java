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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
		var settings = new HashMap<String, String>();
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
				false,
				"not_set",
				null,
				null,
				Set.of(),
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
										 "dependency_graph_autosubmit_action_options": {"labeled_runners": false},
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

	/**
	 * The option sub-objects are compared and sent only when the config sets
	 * them: a labeled runner and a reviewer list here, against a configuration
	 * GitHub answers with no runner and a stray reviewer.
	 */
	@Test
	void options_areComparedAndSentWhenTheConfigSetsThem() {
		stubFor(patch(urlPathEqualTo(BASE + "/7")).willReturn(okJson()));
		var wanted = Desired.codeSecurityConfiguration()
				.withCodeScanningDefaultSetup(Drifty.SecuritySetting.ENABLED)
				.withCodeScanningDefaultSetupOptions(
						new Drifty.CodeScanningDefaultSetupOptions(
								Drifty.CodeScanningRunnerType.LABELED,
								"gpu"
						)
				)
				.withSecretScanningDelegatedBypass(
						Drifty.SecuritySetting.ENABLED
				)
				.withSecretScanningDelegatedBypassOptions(
						new Drifty.SecretScanningDelegatedBypassOptions(
								List.of(
										new Drifty.CodeSecurityBypassReviewer(
												5L,
												Drifty.SecretScanningBypassReviewerType.TEAM,
												Drifty.SecretScanningBypassReviewerMode.ALWAYS
										)
								)
						)
				);
		var current = defaults("baseline", "none", Set.of());
		current = new ActualCodeSecurityConfiguration(
				current.id(),
				current.name(),
				current.description(),
				current.settings(),
				current.enforcement(),
				current.dependencyGraphAutosubmitLabeledRunners(),
				"not_set",
				null,
				null,
				Set.of(
						new ActualCodeSecurityConfiguration.BypassReviewer(
								"ROLE",
								9L,
								"EXEMPT"
						)
				),
				current.defaultForNewRepos(),
				current.repositories()
		);

		var fixes = group(Map.of("baseline", wanted), List.of(current))
				.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_code_security_configurations.baseline.code_scanning_default_setup",
						"org_code_security_configurations.baseline.code_scanning_default_setup_options.runner_type",
						"org_code_security_configurations.baseline.code_scanning_default_setup_options.runner_label",
						"org_code_security_configurations.baseline.secret_scanning_delegated_bypass",
						"org_code_security_configurations.baseline.secret_scanning_delegated_bypass_options.reviewers"
				);
		var settings = fixes.stream()
				.filter(f -> !f.items().isEmpty())
				.findFirst()
				.orElseThrow();
		assertThat(settings.fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(urlPathEqualTo(BASE + "/7")).withRequestBody(
						equalToJson(
								"""
										{"name": "baseline",
										 "code_scanning_default_setup": "enabled",
										 "code_scanning_default_setup_options": {"runner_type": "labeled", "runner_label": "gpu"},
										 "secret_scanning_delegated_bypass": "enabled",
										 "secret_scanning_delegated_bypass_options": {"reviewers": [
										   {"reviewer_id": 5, "reviewer_type": "TEAM", "mode": "ALWAYS"}]}}
										""",
								true,
								true
						)
				)
		);
	}

	/**
	 * A config that leaves the options out says nothing about them: whatever
	 * runner and reviewers GitHub has are no drift, and the PATCH omits both
	 * objects so GitHub keeps them.
	 */
	@Test
	void options_areNeitherComparedNorSentWhenTheConfigLeavesThemOut() {
		stubFor(patch(urlPathEqualTo(BASE + "/7")).willReturn(okJson()));
		var wanted = Desired.codeSecurityConfiguration()
				.withDescription("changed");
		var current = defaults("baseline", "none", Set.of());
		current = new ActualCodeSecurityConfiguration(
				current.id(),
				current.name(),
				current.description(),
				current.settings(),
				current.enforcement(),
				current.dependencyGraphAutosubmitLabeledRunners(),
				"labeled",
				"gpu",
				true,
				Set.of(
						new ActualCodeSecurityConfiguration.BypassReviewer(
								"TEAM",
								5L,
								"ALWAYS"
						)
				),
				current.defaultForNewRepos(),
				current.repositories()
		);

		var fixes = group(Map.of("baseline", wanted), List.of(current))
				.detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_code_security_configurations.baseline.description"
				);
		fixes.getFirst().fix().execute();
		verify(
				patchRequestedFor(urlPathEqualTo(BASE + "/7")).withRequestBody(
						equalToJson(
								"""
										{"name": "baseline", "description": "changed",
										 "advanced_security": "disabled", "dependency_graph": "enabled",
										 "dependency_graph_autosubmit_action": "disabled",
										 "dependency_graph_autosubmit_action_options": {"labeled_runners": false},
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
	}

	/**
	 * The labeled dependency-submission runner is not one of the nullable
	 * option objects: GitHub returns it on every configuration, so it is always
	 * compared and always sent.
	 */
	@Test
	void labeledRunners_areComparedAndSentWithoutAnOptionsObject() {
		stubFor(patch(urlPathEqualTo(BASE + "/7")).willReturn(okJson()));
		var wanted = Desired.codeSecurityConfiguration()
				.withDependencyGraphAutosubmitAction(
						Drifty.SecuritySetting.ENABLED
				)
				.withDependencyGraphAutosubmitLabeledRunners(true);

		var fixes = group(
				Map.of("baseline", wanted),
				List.of(defaults("baseline", "none", Set.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactlyInAnyOrder(
						"org_code_security_configurations.baseline.dependency_graph_autosubmit_action",
						"org_code_security_configurations.baseline.dependency_graph_autosubmit_action_options.labeled_runners"
				);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(urlPathEqualTo(BASE + "/7")).withRequestBody(
						equalToJson(
								"""
										{"dependency_graph_autosubmit_action": "enabled",
										 "dependency_graph_autosubmit_action_options": {"labeled_runners": true}}
										""",
								true,
								true
						)
				)
		);
	}

	/**
	 * {@code code_scanning_options} is one of the nullable objects: set here
	 * against a configuration GitHub answers without one.
	 */
	@Test
	void allowAdvanced_isComparedAndSentWhenTheConfigSetsIt() {
		stubFor(patch(urlPathEqualTo(BASE + "/7")).willReturn(okJson()));
		var wanted = Desired.codeSecurityConfiguration()
				.withCodeScanningOptions(new Drifty.CodeScanningOptions(true));

		var fixes = group(
				Map.of("baseline", wanted),
				List.of(defaults("baseline", "none", Set.of()))
		).detect();

		assertThat(fixes).flatExtracting(DriftFix::items)
				.extracting(DriftItem::path)
				.containsExactly(
						"org_code_security_configurations.baseline.code_scanning_options.allow_advanced"
				);
		assertThat(fixes.getFirst().fix().execute().unfixedItems()).isEmpty();
		verify(
				patchRequestedFor(
						urlPathEqualTo(BASE + "/7")
				).withRequestBody(equalToJson("""
						{"code_scanning_options": {"allow_advanced": true}}
						""", true, true))
		);
	}

	private static ResponseDefinitionBuilder okJson() {
		return aResponse().withStatus(200).withBody("{}");
	}

}
