package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualOrgVariable;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.OrgVariableRequest;
import io.github.arlol.githubcheck.client.SecretVisibility;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Organization Actions variables — {@link OrgActionSecretsDriftGroup} with a
 * value drifty can read, so no state file: the value, the visibility and the
 * selected repositories are compared, and one PATCH (or a POST for a missing
 * variable) writes all three.
 */
public class OrgActionVariablesDriftGroup
		extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.OrgVariable> desired;
	private final Map<String, ActualOrgVariable> actual;
	private final Map<String, Long> repositoryIds;
	private final GitHubClient client;
	private final String org;

	public OrgActionVariablesDriftGroup(
			Map<String, Drifty.OrgVariable> desired,
			List<ActualOrgVariable> actual,
			Map<String, Long> repositoryIds,
			GitHubClient client,
			String org
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		var byName = new LinkedHashMap<String, ActualOrgVariable>();
		for (ActualOrgVariable variable : actual) {
			byName.put(variable.name(), variable);
		}
		this.actual = Collections.unmodifiableMap(byName);
		this.repositoryIds = Map.copyOf(repositoryIds);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_ACTION_VARIABLES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			Drifty.OrgVariable wanted = entry.getValue();
			ActualOrgVariable current = actual.get(name);
			if (current == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(name),
								() -> write(name, wanted, true)
						)
				);
				continue;
			}
			var items = new ArrayList<DriftItem>(
					combine(
							compare(
									name + ".value",
									wanted.value,
									current.value()
							),
							compare(
									name + ".visibility",
									PklTypes.secretVisibility(
											wanted.visibility
									),
									current.visibility()
							)
					)
			);
			// The repository list only exists under "selected"; comparing it
			// when neither side selects would report the empty list GitHub
			// returns for an "all" variable as drift against a configured one.
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
			fixes.add(new DriftFix(items, () -> write(name, wanted, false)));
		}

		for (ActualOrgVariable variable : actual.values()) {
			if (!desired.containsKey(variable.name())) {
				var item = new DriftItem.SectionExtra(variable.name());
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not delete variables it did not create"
								)
						)
				);
			}
		}

		return fixes;
	}

	private FixResult write(
			String name,
			Drifty.OrgVariable wanted,
			boolean create
	) {
		var ids = new ArrayList<Long>();
		if (wanted.visibility == Drifty.SecretVisibility.SELECTED) {
			for (String repository : wanted.selectedRepositories) {
				Long id = repositoryIds.get(repository);
				if (id == null) {
					throw new IllegalStateException(
							"no repository " + repository + " in " + org
					);
				}
				ids.add(id);
			}
		}
		var request = new OrgVariableRequest(
				name,
				wanted.value,
				PklTypes.secretVisibility(wanted.visibility),
				wanted.visibility == Drifty.SecretVisibility.SELECTED ? ids
						: null
		);
		if (create) {
			client.createOrgActionVariable(org, request);
		} else {
			client.updateOrgActionVariable(org, request);
		}
		return FixResult.success();
	}

}
