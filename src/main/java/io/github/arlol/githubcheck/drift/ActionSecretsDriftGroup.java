package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.Secret;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.state.DriftyState;

public class ActionSecretsDriftGroup extends DriftGroup {

	private final List<String> desired;
	private final Map<String, Secret> actual;
	private final Map<String, String> secretValues;
	private final DriftyState state;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ActionSecretsDriftGroup(
			RepositoryArgs desired,
			List<Secret> actual,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = List.copyOf(desired.actionsSecrets());
		var byName = new LinkedHashMap<String, Secret>();
		for (Secret secret : actual) {
			byName.put(secret.name(), secret);
		}
		this.actual = Map.copyOf(byName);
		this.secretValues = Map.copyOf(secretValues);
		this.state = state;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "action_secrets";
	}

	@Override
	public List<DriftFix> detect() {
		var fixes = new ArrayList<DriftFix>();

		for (String secretName : desired) {
			DriftFix fix = secretDriftFix(secretName);
			if (fix != null) {
				fixes.add(fix);
			}
		}

		for (Secret secret : actual.values()) {
			if (!desired.contains(secret.name())) {
				var item = new DriftItem.SectionExtra(
						"action_secrets." + secret.name()
				);
				fixes.add(new DriftFix(item, () -> new FixResult(item)));
			}
		}

		return fixes;
	}

	private DriftFix secretDriftFix(String secretName) {
		var path = "action_secrets." + secretName;
		Secret actualSecret = actual.get(secretName);
		DriftItem driftItem;
		if (actualSecret == null) {
			driftItem = new DriftItem.SectionMissing(path);
		} else {
			var record = state.actionSecretRecord(repo, secretName);
			if (record == null) {
				driftItem = new DriftItem.SecretUnverifiable(path);
			} else if (!Objects
					.equals(record.updatedAt(), actualSecret.updatedAt())) {
				driftItem = new DriftItem.SecretChanged(
						path,
						record.updatedAt(),
						actualSecret.updatedAt()
				);
			} else {
				var value = secretValues.get(repo + "-" + secretName);
				if (value != null
						&& !record.valueHash().equals(state.hash(value))) {
					driftItem = new DriftItem.SecretValueChanged(path);
				} else {
					return null;
				}
			}
		}
		return new DriftFix(driftItem, () -> {
			var value = secretValues.get(repo + "-" + secretName);
			if (value == null) {
				return new FixResult(driftItem);
			}
			client.createOrUpdateActionSecret(owner, repo, secretName, value);
			Secret updated = client.getActionSecret(owner, repo, secretName);
			state.recordActionSecret(
					repo,
					secretName,
					updated.updatedAt(),
					state.hash(value)
			);
			return FixResult.success();
		});
	}

}
