package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualVariable;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.VariableRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Repository Actions variables. Plaintext, so the value is compared directly
 * and no state file is involved: a missing variable is created, a drifted one
 * updated. Variables GitHub has that the config does not name are reported and
 * left alone, the way secrets are.
 */
public class ActionVariablesDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, String> desired;
	private final Map<String, ActualVariable> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public ActionVariablesDriftGroup(
			Map<String, String> desired,
			List<ActualVariable> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = new LinkedHashMap<>(desired);
		this.actual = byName(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	static Map<String, ActualVariable> byName(List<ActualVariable> actual) {
		var byName = new LinkedHashMap<String, ActualVariable>();
		for (ActualVariable variable : actual) {
			byName.put(variable.name(), variable);
		}
		return byName;
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.ACTION_VARIABLES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		return variableFixes(
				"",
				desired,
				actual,
				variable -> client.createActionVariable(owner, repo, variable),
				variable -> client.updateActionVariable(owner, repo, variable)
		);
	}

	/**
	 * The fixes for one set of variables, shared with the environment group:
	 * one per missing or drifted variable, and one unfixable per extra.
	 */
	static List<DriftFix> variableFixes(
			String prefix,
			Map<String, String> desired,
			Map<String, ActualVariable> actual,
			java.util.function.Consumer<VariableRequest> create,
			java.util.function.Consumer<VariableRequest> update
	) {
		var fixes = new ArrayList<DriftFix>();
		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			String value = entry.getValue();
			var request = new VariableRequest(name, value);
			ActualVariable current = actual.get(name);
			if (current == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(prefix + name),
								() -> {
									create.accept(request);
									return FixResult.success();
								}
						)
				);
			} else if (!value.equals(current.value())) {
				fixes.add(
						new DriftFix(
								new DriftItem.FieldMismatch(
										prefix + name,
										value,
										current.value()
								),
								() -> {
									update.accept(request);
									return FixResult.success();
								}
						)
				);
			}
		}
		for (String name : actual.keySet()) {
			if (!desired.containsKey(name)) {
				var item = new DriftItem.SectionExtra(prefix + name);
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

}
