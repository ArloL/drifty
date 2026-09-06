package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.github.arlol.githubcheck.actual.ActualCodeSecurityConfiguration;
import io.github.arlol.githubcheck.client.CodeSecurityConfigurationRequest;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Code security configurations on an organization. Three writes per
 * configuration, each its own {@link DriftFix} so a rejected one is not
 * reported as having failed the others: the settings go to a PATCH (a POST for
 * a missing configuration, which then also applies the other two, since they
 * need the id the POST returns), the default-for-new-repositories value to
 * {@code PUT .../defaults}, and missing attachments to {@code POST
 * .../attach}. Repositories attached outside the config are reported and left
 * attached, and extra configurations are reported and never deleted: deleting
 * one detaches every repository it covers. Only the organization's own
 * configurations reach this group; the checker drops GitHub's global ones.
 */
public class OrgCodeSecurityConfigurationsDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	/** One of the seventeen toggles: its wire name and the config's value. */
	private record Setting(
			String wire,
			Function<Drifty.CodeSecurityConfiguration, Object> wanted
	) {
	}

	private static final List<Setting> SETTINGS = List.of(
			new Setting("advanced_security", c -> c.advancedSecurity),
			new Setting("dependency_graph", c -> c.dependencyGraph),
			new Setting(
					"dependency_graph_autosubmit_action",
					c -> c.dependencyGraphAutosubmitAction
			),
			new Setting("dependabot_alerts", c -> c.dependabotAlerts),
			new Setting(
					"dependabot_security_updates",
					c -> c.dependabotSecurityUpdates
			),
			new Setting(
					"dependabot_delegated_alert_dismissal",
					c -> c.dependabotDelegatedAlertDismissal
			),
			new Setting(
					"code_scanning_default_setup",
					c -> c.codeScanningDefaultSetup
			),
			new Setting(
					"code_scanning_delegated_alert_dismissal",
					c -> c.codeScanningDelegatedAlertDismissal
			),
			new Setting("secret_scanning", c -> c.secretScanning),
			new Setting(
					"secret_scanning_push_protection",
					c -> c.secretScanningPushProtection
			),
			new Setting(
					"secret_scanning_delegated_bypass",
					c -> c.secretScanningDelegatedBypass
			),
			new Setting(
					"secret_scanning_validity_checks",
					c -> c.secretScanningValidityChecks
			),
			new Setting(
					"secret_scanning_non_provider_patterns",
					c -> c.secretScanningNonProviderPatterns
			),
			new Setting(
					"secret_scanning_generic_secrets",
					c -> c.secretScanningGenericSecrets
			),
			new Setting(
					"secret_scanning_delegated_alert_dismissal",
					c -> c.secretScanningDelegatedAlertDismissal
			),
			new Setting(
					"private_vulnerability_reporting",
					c -> c.privateVulnerabilityReporting
			)
	);

	private final Map<String, Drifty.CodeSecurityConfiguration> desired;
	private final Map<String, ActualCodeSecurityConfiguration> actual;
	private final Map<String, Long> repositoryIds;
	private final GitHubClient client;
	private final String org;

	public OrgCodeSecurityConfigurationsDriftGroup(
			Map<String, Drifty.CodeSecurityConfiguration> desired,
			List<ActualCodeSecurityConfiguration> actual,
			Map<String, Long> repositoryIds,
			GitHubClient client,
			String org
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		var byName = new LinkedHashMap<String, ActualCodeSecurityConfiguration>();
		for (ActualCodeSecurityConfiguration configuration : actual) {
			byName.put(configuration.name(), configuration);
		}
		this.actual = Collections.unmodifiableMap(byName);
		this.repositoryIds = Map.copyOf(repositoryIds);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_CODE_SECURITY_CONFIGURATIONS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			Drifty.CodeSecurityConfiguration wanted = entry.getValue();
			ActualCodeSecurityConfiguration current = actual.get(name);
			if (current == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(name),
								() -> create(name, wanted)
						)
				);
				continue;
			}
			fixes.add(
					new DriftFix(compareSettings(name, wanted, current), () -> {
						client.updateCodeSecurityConfiguration(
								org,
								current.id(),
								request(name, wanted)
						);
						return FixResult.success();
					})
			);
			fixes.add(
					new DriftFix(
							compare(
									name + ".default_for_new_repos",
									wanted.defaultForNewRepos.toString(),
									current.defaultForNewRepos()
							),
							() -> {
								client.setCodeSecurityDefaults(
										org,
										current.id(),
										wanted.defaultForNewRepos.toString()
								);
								return FixResult.success();
							}
					)
			);
			fixes.addAll(compareRepositories(name, wanted, current));
		}

		for (ActualCodeSecurityConfiguration configuration : actual.values()) {
			if (!desired.containsKey(configuration.name())) {
				var item = new DriftItem.SectionExtra(configuration.name());
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not delete code security configurations: deleting one detaches every repository it covers"
								)
						)
				);
			}
		}

		return fixes;
	}

	private static List<DriftItem> compareSettings(
			String name,
			Drifty.CodeSecurityConfiguration wanted,
			ActualCodeSecurityConfiguration current
	) {
		var items = new ArrayList<DriftItem>(
				compare(
						name + ".description",
						wanted.description,
						current.description()
				)
		);
		for (Setting setting : SETTINGS) {
			items.addAll(
					compare(
							name + "." + setting.wire(),
							setting.wanted().apply(wanted).toString(),
							current.settings().get(setting.wire())
					)
			);
		}
		items.addAll(
				compare(
						name + ".enforcement",
						wanted.enforcement.toString(),
						current.enforcement()
				)
		);
		return items;
	}

	/**
	 * Missing attachments are one fix, the attach call; each repository
	 * attached outside the config is its own reported-only item.
	 */
	private List<DriftFix> compareRepositories(
			String name,
			Drifty.CodeSecurityConfiguration wanted,
			ActualCodeSecurityConfiguration current
	) {
		var fixes = new ArrayList<DriftFix>();
		Set<String> missing = new HashSet<>(wanted.repositories);
		missing.removeAll(current.repositories());
		if (!missing.isEmpty()) {
			var item = new DriftItem.SetDrift(
					name + ".repositories",
					missing,
					Set.of()
			);
			fixes.add(
					new DriftFix(
							item,
							() -> attach(item, current.id(), missing)
					)
			);
		}
		for (String repository : current.repositories()) {
			if (!wanted.repositories.contains(repository)) {
				var item = new DriftItem.SectionExtra(
						name + ".repositories." + repository
				);
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not detach repositories from a code security configuration"
								)
						)
				);
			}
		}
		return fixes;
	}

	private FixResult create(
			String name,
			Drifty.CodeSecurityConfiguration wanted
	) {
		var created = client
				.createCodeSecurityConfiguration(org, request(name, wanted));
		if (wanted.defaultForNewRepos != Drifty.DefaultForNewRepos.NONE) {
			client.setCodeSecurityDefaults(
					org,
					created.id(),
					wanted.defaultForNewRepos.toString()
			);
		}
		if (!wanted.repositories.isEmpty()) {
			var item = new DriftItem.SectionMissing(name);
			return attach(item, created.id(), wanted.repositories);
		}
		return FixResult.success();
	}

	private FixResult attach(
			DriftItem item,
			long configurationId,
			Iterable<String> repositories
	) {
		var ids = new ArrayList<Long>();
		for (String repository : repositories) {
			Long id = repositoryIds.get(repository);
			if (id == null) {
				return FixResult.unfixed(
						item,
						"no repository " + repository + " in " + org
				);
			}
			ids.add(id);
		}
		client.attachCodeSecurityConfiguration(org, configurationId, ids);
		return FixResult.success();
	}

	private static CodeSecurityConfigurationRequest request(
			String name,
			Drifty.CodeSecurityConfiguration c
	) {
		return new CodeSecurityConfigurationRequest(
				name,
				c.description,
				c.advancedSecurity.toString(),
				c.dependencyGraph.toString(),
				c.dependencyGraphAutosubmitAction.toString(),
				c.dependabotAlerts.toString(),
				c.dependabotSecurityUpdates.toString(),
				c.dependabotDelegatedAlertDismissal.toString(),
				c.codeScanningDefaultSetup.toString(),
				c.codeScanningDelegatedAlertDismissal.toString(),
				c.secretScanning.toString(),
				c.secretScanningPushProtection.toString(),
				c.secretScanningDelegatedBypass.toString(),
				c.secretScanningValidityChecks.toString(),
				c.secretScanningNonProviderPatterns.toString(),
				c.secretScanningGenericSecrets.toString(),
				c.secretScanningDelegatedAlertDismissal.toString(),
				c.privateVulnerabilityReporting.toString(),
				c.enforcement.toString()
		);
	}

}
