package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Organization rulesets — {@link RulesetDriftGroup} on the organization
 * endpoints, plus the repository conditions only an organization ruleset has.
 * Everything else is {@link RulesetComparison}'s. Extra rulesets are deleted by
 * {@code --fix}, since the config can recreate one whole; enterprise-owned
 * rulesets never reach this group, the checker drops them.
 */
public class OrgRulesetDriftGroup extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.OrgRuleset> desired;
	private final List<ActualRuleset> actual;
	private final GitHubClient client;
	private final String org;

	public OrgRulesetDriftGroup(
			Map<String, Drifty.OrgRuleset> desired,
			List<ActualRuleset> actual,
			GitHubClient client,
			String org
	) {
		this.desired = Map.copyOf(desired);
		this.actual = List.copyOf(actual);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_RULESETS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		Map<String, ActualRuleset> actualByName = actual.stream()
				.collect(
						Collectors
								.toMap(ActualRuleset::name, r -> r, (a, _) -> a)
				);

		for (var entry : desired.entrySet()) {
			String rName = entry.getKey();
			Drifty.OrgRuleset wanted = entry.getValue();
			ActualRuleset got = actualByName.get(rName);

			if (got == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(rName),
								() -> {
									client.createOrgRuleset(
											org,
											request(rName, wanted)
									);
									return FixResult.success();
								}
						)
				);
				continue;
			}

			var items = new ArrayList<>(
					RulesetComparison.compare(rName, wanted, got)
			);
			compareRepositoryConditions(rName, wanted, got, items);
			if (!items.isEmpty()) {
				final var gotId = got.id();
				fixes.add(new DriftFix(items, () -> {
					client.updateOrgRuleset(org, gotId, request(rName, wanted));
					return FixResult.success();
				}));
			}
		}

		actual.stream()
				.filter(extra -> !desired.containsKey(extra.name()))
				.map(this::deleteExtraFix)
				.forEach(fixes::add);

		return fixes;
	}

	/**
	 * A ruleset with no repository condition applies to every repository, and
	 * GitHub answers such a ruleset with an empty name condition; the two read
	 * the same here.
	 */
	private static void compareRepositoryConditions(
			String rName,
			Drifty.OrgRuleset wanted,
			ActualRuleset got,
			List<DriftItem> items
	) {
		DriftGroup
				.ocompare(
						rName + ".repository_name.include",
						new HashSet<>(wanted.repositoryNameInclude),
						got.repositoryNameInclude()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						rName + ".repository_name.exclude",
						new HashSet<>(wanted.repositoryNameExclude),
						got.repositoryNameExclude()
				)
				.ifPresent(items::add);
		DriftGroup
				.ocompare(
						rName + ".repository_name.protected",
						wanted.repositoryNameProtected,
						got.repositoryNameProtected()
				)
				.ifPresent(items::add);
		RulesetComparison.compareIfAnyPresent(
				rName + ".repository_property.include",
				propertyConditions(wanted.repositoryPropertyInclude),
				got.repositoryPropertyInclude()
						.stream()
						.map(Object::toString)
						.collect(Collectors.toSet()),
				items
		);
		RulesetComparison.compareIfAnyPresent(
				rName + ".repository_property.exclude",
				propertyConditions(wanted.repositoryPropertyExclude),
				got.repositoryPropertyExclude()
						.stream()
						.map(Object::toString)
						.collect(Collectors.toSet()),
				items
		);
	}

	private static Set<String> propertyConditions(
			List<Drifty.PropertyCondition> conditions
	) {
		return conditions.stream()
				.map(
						c -> new ActualRuleset.PropertyCondition(
								c.name,
								new HashSet<>(c.propertyValues),
								c.source
						).toString()
				)
				.collect(Collectors.toSet());
	}

	private DriftFix deleteExtraFix(ActualRuleset extra) {
		return new DriftFix(new DriftItem.SectionExtra(extra.name()), () -> {
			client.deleteOrgRuleset(org, extra.id());
			return FixResult.success();
		});
	}

	/**
	 * The request always carries a repository name condition: GitHub requires
	 * one on an organization ruleset, and an empty include list is how "every
	 * repository" is spelled. The property condition is only sent when the
	 * config names one, since an empty one is rejected.
	 */
	private static RulesetRequest request(String name, Drifty.OrgRuleset args) {
		var repositoryName = new RulesetRequest.Conditions.RepositoryName(
				args.repositoryNameInclude,
				args.repositoryNameExclude,
				args.repositoryNameProtected ? Boolean.TRUE : null
		);
		RulesetRequest.Conditions.RepositoryProperty repositoryProperty = null;
		if (!args.repositoryPropertyInclude.isEmpty()
				|| !args.repositoryPropertyExclude.isEmpty()) {
			repositoryProperty = new RulesetRequest.Conditions.RepositoryProperty(
					propertyConditionRequests(args.repositoryPropertyInclude),
					propertyConditionRequests(args.repositoryPropertyExclude)
			);
		}
		return RulesetComparison.request(
				name,
				args,
				new RulesetRequest.Conditions(
						RulesetComparison.refName(args),
						repositoryName,
						null,
						repositoryProperty
				)
		);
	}

	private static List<RulesetRequest.Conditions.RepositoryProperty.PropertyCondition> propertyConditionRequests(
			List<Drifty.PropertyCondition> conditions
	) {
		return conditions.stream()
				.map(
						c -> new RulesetRequest.Conditions.RepositoryProperty.PropertyCondition(
								c.name,
								c.propertyValues,
								c.source
						)
				)
				.toList();
	}

}
