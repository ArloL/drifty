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

public class ActionSecretsDriftGroup extends DriftGroup {

	private final List<String> desired;
	private final Map<String, ActualSecret> actual;
	private final Map<String, String> secretValues;
	private final DriftyState state;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ActionSecretsDriftGroup(
			List<String> desired,
			List<ActualSecret> actual,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = List.copyOf(desired);
		var byName = new LinkedHashMap<String, ActualSecret>();
		for (ActualSecret secret : actual) {
			byName.put(secret.name(), secret);
		}
		this.actual = Map.copyOf(byName);
		this.secretValues = Map.copyOf(secretValues);
		this.state = state;
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public String name() {
		return "action_secrets";
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (String secretName : desired) {
			DriftFix fix = secretDriftFix(secretName);
			if (fix != null) {
				fixes.add(fix);
			}
		}

		for (ActualSecret secret : actual.values()) {
			if (!desired.contains(secret.name())) {
				var item = new DriftItem.SectionExtra(secret.name());
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

		return fixes;
	}

	private DriftFix secretDriftFix(String secretName) {
		var path = secretName;
		ActualSecret actualSecret = actual.get(secretName);
		DriftItem driftItem;
		if (actualSecret == null) {
			driftItem = new DriftItem.SectionMissing(path);
		} else {
			var record = state.actionSecretRecord(repo, secretName);
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
			var key = repo + "-" + secretName;
			var value = secretValues.get(key);
			if (value == null) {
				return FixResult.unfixed(
						driftItem,
						"no value for " + key + " in DRIFTY_GITHUB_SECRETS"
				);
			}
			client.createOrUpdateActionSecret(owner, repo, secretName, value);
			var updated = client.getActionSecret(owner, repo, secretName);
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
