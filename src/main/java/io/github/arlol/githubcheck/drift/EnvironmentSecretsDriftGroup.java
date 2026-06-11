package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.config.EnvironmentArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.state.DriftyState;

public class EnvironmentSecretsDriftGroup extends DriftGroup {

	private final Map<String, EnvironmentArgs> desired;
	private final Map<String, List<Secret>> actual;
	private final Map<String, String> secretValues;
	private final DriftyState state;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentSecretsDriftGroup(
			RepositoryArgs desired,
			Map<String, List<Secret>> actual,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Map.copyOf(desired.environments());
		this.actual = Map.copyOf(actual);
		this.secretValues = Map.copyOf(secretValues);
		this.state = state;
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

			List<Secret> actualSecrets = actual
					.getOrDefault(envName, List.of());
			var byName = new LinkedHashMap<String, Secret>();
			for (Secret secret : actualSecrets) {
				byName.put(secret.name(), secret);
			}

			for (String secretName : wantEnv.secrets()) {
				DriftFix fix = secretDriftFix(secretName, envName, byName);
				if (fix != null) {
					fixes.add(fix);
				}
			}

			for (Secret secret : actualSecrets) {
				if (!wantEnv.secrets().contains(secret.name())) {
					var item = new DriftItem.SectionExtra(
							"environment." + envName + ".secrets."
									+ secret.name()
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
			Map<String, Secret> actualByName
	) {
		var path = "environment." + envName + ".secrets." + secretName;
		Secret actualSecret = actualByName.get(secretName);
		DriftItem driftItem;
		if (actualSecret == null) {
			driftItem = new DriftItem.SectionMissing(path);
		} else {
			var record = state
					.environmentSecretRecord(repo, envName, secretName);
			if (record == null) {
				driftItem = new DriftItem.SecretMissingBaseline(path);
			} else if (!Objects
					.equals(record.updatedAt(), actualSecret.updatedAt())) {
				driftItem = new DriftItem.SecretChanged(
						path,
						record.updatedAt(),
						actualSecret.updatedAt()
				);
			} else {
				var value = secretValues
						.get(repo + "-" + envName + "-" + secretName);
				if (value != null
						&& !record.valueHash().equals(state.hash(value))) {
					driftItem = new DriftItem.SecretValueChanged(path);
				} else {
					return null;
				}
			}
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
			Secret updated = client
					.getEnvironmentSecret(owner, repo, envName, secretName);
			state.recordEnvironmentSecret(
					repo,
					envName,
					secretName,
					updated.updatedAt(),
					state.hash(value)
			);
			return FixResult.success();
		});
	}

}
