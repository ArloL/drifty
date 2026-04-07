package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class ActionSecretsDriftGroup extends DriftGroup {

	private final Set<String> desired;
	private final Set<String> actual;
	private final Map<String, String> secretValues;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ActionSecretsDriftGroup(
			RepositoryArgs desired,
			Set<String> actual,
			Map<String, String> secretValues,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desired = Set.copyOf(desired.actionsSecrets());
		this.actual = Set.copyOf(actual);
		this.secretValues = Map.copyOf(secretValues);
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
			fixes.add(secretDriftFix(secretName));
		}

		for (String secretName : actual) {
			if (!desired.contains(secretName)) {
				var item = new DriftItem.SectionExtra(
						"action_secrets." + secretName
				);
				fixes.add(new DriftFix(item, () -> new FixResult(item)));
			}
		}

		return fixes;
	}

	private DriftFix secretDriftFix(String secretName) {
		var path = "action_secrets." + secretName;
		DriftItem driftItem;
		if (actual.contains(secretName)) {
			driftItem = new DriftItem.SecretUnverifiable(path);
		} else {
			driftItem = new DriftItem.SectionMissing(path);
		}
		return new DriftFix(driftItem, () -> {
			var value = secretValues.get(repo + "-" + secretName);
			if (value == null) {
				return new FixResult(driftItem);
			}
			client.createOrUpdateActionSecret(owner, repo, secretName, value);
			return FixResult.success();
		});
	}

}
