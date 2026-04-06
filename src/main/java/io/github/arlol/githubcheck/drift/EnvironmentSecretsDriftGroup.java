package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretPublicKeyResponse;
import io.github.arlol.githubcheck.client.SecretRequest;
import io.github.arlol.githubcheck.config.EnvironmentArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.OrgChecker;

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
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();

			if (wantEnv.secrets().isEmpty()) {
				continue;
			}

			Set<String> wantSet = new HashSet<>(wantEnv.secrets());
			Set<String> gotSet = new HashSet<>(
					actualSecretNames.getOrDefault(envName, List.of())
			);

			for (String secretName : wantEnv.secrets()) {
				String path = "environment." + envName + ".secrets."
						+ secretName;
				if (gotSet.contains(secretName)) {
					items.add(new DriftItem.SecretUnverifiable(path));
				} else {
					items.add(new DriftItem.SectionMissing(path));
				}
			}

			for (String secretName : gotSet) {
				if (!wantSet.contains(secretName)) {
					items.add(
							new DriftItem.SectionExtra(
									"environment." + envName + ".secrets."
											+ secretName
							)
					);
				}
			}
		}

		return items;
	}

	@Override
	public FixResult fix() {
		var unfixed = new ArrayList<DriftItem>();

		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			EnvironmentArgs wantEnv = entry.getValue();

			Set<String> wantSet = new HashSet<>(wantEnv.secrets());
			Set<String> gotSet = new HashSet<>(
					actualSecretNames.getOrDefault(envName, List.of())
			);

			for (String secretName : wantEnv.secrets()) {
				String path = "environment." + envName + ".secrets."
						+ secretName;
				String mapKey = repo + "-" + envName + "-" + secretName;
				String value = secretValues.get(mapKey);
				if (value == null) {
					if (gotSet.contains(secretName)) {
						unfixed.add(new DriftItem.SecretUnverifiable(path));
					} else {
						unfixed.add(new DriftItem.SectionMissing(path));
					}
					continue;
				}

				SecretPublicKeyResponse publicKey = client
						.getEnvironmentSecretPublicKey(owner, repo, envName);
				String encrypted = OrgChecker
						.encryptSecret(publicKey.key(), value);
				client.createOrUpdateEnvironmentSecret(
						owner,
						repo,
						envName,
						secretName,
						new SecretRequest(encrypted, publicKey.keyId())
				);
			}

			for (String secretName : gotSet) {
				if (!wantSet.contains(secretName)) {
					unfixed.add(
							new DriftItem.SectionExtra(
									"environment." + envName + ".secrets."
											+ secretName
							)
					);
				}
			}
		}

		return new FixResult(unfixed);
	}

}
