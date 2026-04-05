package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretPublicKeyResponse;
import io.github.arlol.githubcheck.client.SecretRequest;
import io.github.arlol.githubcheck.config.RepositoryArgs;
import io.github.arlol.githubcheck.OrgChecker;

public class ActionSecretsDriftGroup extends DriftGroup {

	private final List<String> desired;
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
		this.desired = List.copyOf(desired.actionsSecrets());
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
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();

		Set<String> wantSet = new HashSet<>(desired);
		Set<String> gotSet = new HashSet<>(actual);

		Set<String> missing = new HashSet<>(wantSet);
		missing.removeAll(gotSet);

		Set<String> extra = new HashSet<>(gotSet);
		extra.removeAll(wantSet);

		if (!missing.isEmpty() || !extra.isEmpty()) {
			items.add(new DriftItem.SetDrift("action_secrets", missing, extra));
		}

		return items;
	}

	@Override
	public void fix() {
		Set<String> wantSet = new HashSet<>(desired);
		Set<String> gotSet = new HashSet<>(actual);

		Set<String> missing = new HashSet<>(wantSet);
		missing.removeAll(gotSet);

		for (String secretName : missing) {
			String mapKey = repo + "-" + secretName;
			String value = secretValues.get(mapKey);
			if (value == null) {
				continue;
			}

			SecretPublicKeyResponse publicKey = client
					.getActionSecretPublicKey(owner, repo);
			String encrypted = OrgChecker.encryptSecret(publicKey.key(), value);
			client.createOrUpdateActionSecret(
					owner,
					repo,
					secretName,
					new SecretRequest(encrypted, publicKey.keyId())
			);
		}
	}

}
