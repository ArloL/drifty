package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RunnerGroupRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Self-hosted runner groups on an organization, matched by name. A missing
 * group is one POST, which takes the repository selection too; an existing
 * one's settings go to a PATCH and its selection to its own PUT, each its own
 * fix. The group GitHub marks as the default is never extra and never deleted;
 * every other group the config does not declare is deleted by {@code --fix},
 * since a group is nothing but its settings.
 */
public class OrgRunnerGroupsDriftGroup extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.RunnerGroup> desired;
	private final Map<String, ActualRunnerGroup> actual;
	private final Map<String, Long> repositoryIds;
	private final GitHubClient client;
	private final String org;

	public OrgRunnerGroupsDriftGroup(
			Map<String, Drifty.RunnerGroup> desired,
			List<ActualRunnerGroup> actual,
			Map<String, Long> repositoryIds,
			GitHubClient client,
			String org
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		var byName = new LinkedHashMap<String, ActualRunnerGroup>();
		for (ActualRunnerGroup group : actual) {
			byName.put(group.name(), group);
		}
		this.actual = Collections.unmodifiableMap(byName);
		this.repositoryIds = Map.copyOf(repositoryIds);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_RUNNER_GROUPS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String name = entry.getKey();
			Drifty.RunnerGroup wanted = entry.getValue();
			ActualRunnerGroup current = actual.get(name);
			if (current == null) {
				var item = new DriftItem.SectionMissing(name);
				fixes.add(new DriftFix(item, () -> create(item, name, wanted)));
				continue;
			}
			fixes.add(
					new DriftFix(compareSettings(name, wanted, current), () -> {
						client.updateRunnerGroup(
								org,
								current.id(),
								request(name, wanted, null)
						);
						return FixResult.success();
					})
			);
			boolean selected = wanted.visibility == Drifty.RunnerGroupVisibility.SELECTED
					|| "selected".equals(current.visibility());
			if (selected) {
				var items = compare(
						name + ".selected_repositories",
						wanted.selectedRepositories,
						current.selectedRepositories()
				);
				fixes.add(new DriftFix(items, () -> {
					List<Long> ids = ids(wanted.selectedRepositories);
					if (ids == null) {
						return unfixAll(items, wanted.selectedRepositories);
					}
					client.setRunnerGroupRepositories(org, current.id(), ids);
					return FixResult.success();
				}));
			}
		}

		for (ActualRunnerGroup group : actual.values()) {
			if (!desired.containsKey(group.name()) && !group.isDefault()) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionExtra(group.name()),
								() -> {
									client.deleteRunnerGroup(org, group.id());
									return FixResult.success();
								}
						)
				);
			}
		}

		return fixes;
	}

	private static List<DriftItem> compareSettings(
			String name,
			Drifty.RunnerGroup wanted,
			ActualRunnerGroup current
	) {
		return combine(
				compare(
						name + ".visibility",
						wanted.visibility.toString(),
						current.visibility()
				),
				compare(
						name + ".allows_public_repositories",
						wanted.allowsPublicRepositories,
						current.allowsPublicRepositories()
				),
				compare(
						name + ".restricted_to_workflows",
						wanted.restrictedToWorkflows,
						current.restrictedToWorkflows()
				),
				compare(
						name + ".selected_workflows",
						new HashSet<>(wanted.selectedWorkflows),
						current.selectedWorkflows()
				)
		);
	}

	private FixResult create(
			DriftItem item,
			String name,
			Drifty.RunnerGroup wanted
	) {
		List<Long> ids = null;
		if (wanted.visibility == Drifty.RunnerGroupVisibility.SELECTED) {
			ids = ids(wanted.selectedRepositories);
			if (ids == null) {
				return unfixAll(List.of(item), wanted.selectedRepositories);
			}
		}
		client.createRunnerGroup(org, request(name, wanted, ids));
		return FixResult.success();
	}

	private static RunnerGroupRequest request(
			String name,
			Drifty.RunnerGroup wanted,
			List<Long> selectedRepositoryIds
	) {
		return new RunnerGroupRequest(
				name,
				wanted.visibility.toString(),
				selectedRepositoryIds,
				wanted.allowsPublicRepositories,
				wanted.restrictedToWorkflows,
				wanted.selectedWorkflows
		);
	}

	/** The ids of the named repositories, or null when one is unknown. */
	private List<Long> ids(List<String> repositories) {
		var ids = new ArrayList<Long>();
		for (String repository : repositories) {
			Long id = repositoryIds.get(repository);
			if (id == null) {
				return null;
			}
			ids.add(id);
		}
		return ids;
	}

	private FixResult unfixAll(
			List<DriftItem> items,
			List<String> repositories
	) {
		String missing = repositories.stream()
				.filter(r -> !repositoryIds.containsKey(r))
				.findFirst()
				.orElse("?");
		String reason = "no repository " + missing + " in " + org;
		return new FixResult(
				items.stream()
						.map(item -> new FixResult.Unfixed(item, reason))
						.toList()
		);
	}

}
