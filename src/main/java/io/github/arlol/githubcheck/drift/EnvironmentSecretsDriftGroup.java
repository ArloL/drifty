package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.config.EnvironmentArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class EnvironmentSecretsDriftGroup extends DriftGroup {

	private final Map<String, EnvironmentArgs> desired;
	private final Map<String, List<String>> actualSecretNames;
	private final Map<String, String> secretValues;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentSecretsDriftGroup(
			RepositoryArgs desired,
			Map<String, List<String>> actualSecretNames,
			Map<String, String> secretValues,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Map.copyOf(desired.environments());
		this.actualSecretNames = Map.copyOf(actualSecretNames);
		this.secretValues = Map.copyOf(secretValues);
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "environment_secrets";
	}

	@Override
	public List<DriftFix> detect() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();

			List<String> actual = actualSecretNames
					.getOrDefault(envName, List.of());

			for (String secretName : wantEnv.secrets()) {
				fixes.add(secretDriftFix(secretName, envName, actual));
			}

			for (String secretName : actual) {
				if (!wantEnv.secrets().contains(secretName)) {
					var item = new DriftItem.SectionExtra(
							"environment." + envName + ".secrets." + secretName
					);
					fixes.add(new DriftFix(item, () -> new FixResult(item)));
				}
			}
		}

		return fixes;
	}

	private DriftFix secretDriftFix(
			String secretName,
			String envName,
			List<String> actual
	) {
		var path = "environment." + envName + ".secrets." + secretName;
		DriftItem driftItem;
		if (actual.contains(secretName)) {
			driftItem = new DriftItem.SecretUnverifiable(path);
		} else {
			driftItem = new DriftItem.SectionMissing(path);
		}
		return new DriftFix(driftItem, () -> {
			var value = secretValues
					.get(repo + "-" + envName + "-" + secretName);
			if (value == null) {
				return new FixResult(driftItem);
			}
			client.createOrUpdateEnvironmentSecret(
					owner,
					repo,
					envName,
					secretName,
					value
			);
			return FixResult.success();
		});
	}

}
