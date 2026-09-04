package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualOrgSecret;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;
import io.github.arlol.githubcheck.state.DriftyState;

/**
 * Organization Actions secrets — the org twin of
 * {@link ActionSecretsDriftGroup}, with a visibility the repository endpoint
 * has no equivalent of.
 * <p>
 * GitHub never returns a secret's value, so drift is read off two recorded
 * fingerprints: the {@code updated_at} that was observed after the last push
 * (someone changed the secret outside drifty) and the salted hash of the value
 * that was pushed (the configured value was rotated).
 * <p>
 * A secret's own item and its visibility item share one {@link DriftFix}: a
 * single PUT resolves both, and one that cannot run reports every item it
 * carried as unfixed.
 */
public class OrgActionSecretsDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.OrgSecret> desired;
	private final Map<String, ActualOrgSecret> actual;
	private final Map<String, Long> repositoryIds;
	private final Map<String, String> secretValues;
	private final DriftyState state;
	private final GitHubClient client;
	private final String org;

	public OrgActionSecretsDriftGroup(
			Map<String, Drifty.OrgSecret> desired,
			List<ActualOrgSecret> actual,
			Map<String, Long> repositoryIds,
			Map<String, String> secretValues,
			DriftyState state,
			GitHubClient client,
			String org
	) {
		this.desired = Map.copyOf(desired);
		var byName = new LinkedHashMap<String, ActualOrgSecret>();
		for (ActualOrgSecret secret : actual) {
			byName.put(secret.name(), secret);
		}
		this.actual = Map.copyOf(byName);
		this.repositoryIds = Map.copyOf(repositoryIds);
		this.secretValues = Map.copyOf(secretValues);
		this.state = state;
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_ACTION_SECRETS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			DriftFix fix = secretDriftFix(entry.getKey(), entry.getValue());
			if (fix != null) {
				fixes.add(fix);
			}
		}

		for (ActualOrgSecret secret : actual.values()) {
			if (!desired.containsKey(secret.name())) {
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

	private DriftFix secretDriftFix(String name, Drifty.OrgSecret wanted) {
		ActualOrgSecret current = actual.get(name);
		var items = new ArrayList<DriftItem>();
		if (current == null) {
			items.add(new DriftItem.SectionMissing(name));
		} else {
			var record = state.orgActionSecretRecord(org, name);
			if (record == null) {
				items.add(new DriftItem.SecretMissingBaseline(name));
			} else if (!Objects
					.equals(record.updatedAt(), current.updatedAt())) {
				items.add(
						new DriftItem.SecretChanged(
								name,
								record.updatedAt(),
								current.updatedAt()
						)
				);
			} else {
				var value = secretValues.get(key(name));
				if (value != null
						&& !record.valueHash().equals(state.hash(value))) {
					items.add(new DriftItem.SecretValueChanged(name));
				}
			}
			items.addAll(
					compare(
							name + ".visibility",
							PklTypes.secretVisibility(wanted.visibility),
							current.visibility()
					)
			);
			// The repository list only exists under "selected"; comparing it
			// when neither side selects would report the empty list GitHub
			// returns for an "all" secret as drift against a configured one.
			if (wanted.visibility == Drifty.SecretVisibility.SELECTED
					|| current.visibility() == SecretVisibility.SELECTED) {
				items.addAll(
						compare(
								name + ".selected_repositories",
								wanted.selectedRepositories,
								current.selectedRepositories()
						)
				);
			}
		}
		if (items.isEmpty()) {
			return null;
		}
		return new DriftFix(items, () -> push(name, wanted, items));
	}

	private String key(String name) {
		return "org-" + org + "-" + name;
	}

	/**
	 * Pushes the value even when only the visibility drifted: the PUT requires
	 * {@code encrypted_value}, so there is no way to move a secret between
	 * visibilities without re-sending it.
	 */
	private FixResult push(
			String name,
			Drifty.OrgSecret wanted,
			List<DriftItem> items
	) {
		String value = secretValues.get(key(name));
		if (value == null) {
			return unfixAll(
					items,
					"no value for " + key(name) + " in DRIFTY_GITHUB_SECRETS"
			);
		}
		var ids = new ArrayList<Long>();
		for (String repository : wanted.selectedRepositories) {
			Long id = repositoryIds.get(repository);
			// Pushing the ids that did resolve would share the secret with
			// fewer repositories than the config asks for, and report success.
			if (id == null) {
				return unfixAll(
						items,
						"no repository " + repository + " in " + org
				);
			}
			ids.add(id);
		}
		client.createOrUpdateOrgActionSecret(
				org,
				name,
				value,
				PklTypes.secretVisibility(wanted.visibility),
				ids
		);
		var updated = client.getOrgActionSecret(org, name);
		state.recordOrgActionSecret(
				org,
				name,
				updated.updatedAt(),
				state.hash(value)
		);
		return FixResult.success();
	}

	private static FixResult unfixAll(List<DriftItem> items, String reason) {
		return new FixResult(
				items.stream()
						.map(item -> new FixResult.Unfixed(item, reason))
						.toList()
		);
	}

}
