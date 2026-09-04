package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.arlol.githubcheck.actual.ActualSecret;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

public class EnvironmentSecretsDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, Drifty.Environment> desired;
	private final Map<String, List<ActualSecret>> actual;
	private final Map<String, String> secretValues;
	private final DriftyState state;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentSecretsDriftGroup(
			Map<String, Drifty.Environment> desired,
			Map<String, List<ActualSecret>> actual,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = Map.copyOf(desired);
		this.actual = Map.copyOf(actual);
		this.secretValues = Map.copyOf(secretValues);
		this.state = state;
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.ENVIRONMENT_SECRETS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			Drifty.Environment wantEnv = entry.getValue();

			List<ActualSecret> actualSecrets = actual
					.getOrDefault(envName, List.of());
			var byName = new LinkedHashMap<String, ActualSecret>();
			for (ActualSecret secret : actualSecrets) {
				byName.put(secret.name(), secret);
			}

			for (String secretName : wantEnv.secrets) {
				DriftFix fix = secretDriftFix(secretName, envName, byName);
				if (fix != null) {
					fixes.add(fix);
				}
			}

			for (ActualSecret secret : actualSecrets) {
				if (!wantEnv.secrets.contains(secret.name())) {
					var item = new DriftItem.SectionExtra(
							envName + ".secrets." + secret.name()
					);
					fixes.add(
							new DriftFix(
									item,
									() -> FixResult.unfixed(
											item,
											"drifty does not delete secrets it did not create"
									)
							)
					);
				}
			}
		}

		return fixes;
	}

	private DriftFix secretDriftFix(
			String secretName,
			String envName,
			Map<String, ActualSecret> actualByName
	) {
		var path = envName + ".secrets." + secretName;
		ActualSecret actualSecret = actualByName.get(secretName);
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
			var key = repo + "-" + envName + "-" + secretName;
			var value = secretValues.get(key);
			if (value == null) {
				return FixResult.unfixed(
						driftItem,
						"no value for " + key + " in DRIFTY_GITHUB_SECRETS"
				);
			}
			client.createOrUpdateEnvironmentSecret(
					owner,
					repo,
					envName,
					secretName,
					value
			);
			var updated = client
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
