package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Environment Actions variables — {@link ActionVariablesDriftGroup} once per
 * configured environment, on the environment's own endpoints. An environment
 * the config declares but GitHub lacks reports every variable missing; the
 * {@code environment_config} group is what creates the environment, and its fix
 * runs in the same pass.
 */
public class EnvironmentVariablesDriftGroup
		extends DriftGroup<Drifty.GroupName> {

	private final Map<String, Drifty.Environment> desired;
	private final Map<String, List<ActualVariable>> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public EnvironmentVariablesDriftGroup(
			Map<String, Drifty.Environment> desired,
			Map<String, List<ActualVariable>> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = new LinkedHashMap<>(desired);
		this.actual = new LinkedHashMap<>(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.ENVIRONMENT_VARIABLES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();
		for (var entry : desired.entrySet()) {
			String envName = entry.getKey();
			fixes.addAll(
					ActionVariablesDriftGroup.variableFixes(
							envName + ".variables.",
							entry.getValue().variables,
							ActionVariablesDriftGroup.byName(
									actual.getOrDefault(envName, List.of())
							),
							variable -> client.createEnvironmentVariable(
									owner,
									repo,
									envName,
									variable
							),
							variable -> client.updateEnvironmentVariable(
									owner,
									repo,
									envName,
									variable
							)
					)
			);
		}
		return fixes;
	}

}
