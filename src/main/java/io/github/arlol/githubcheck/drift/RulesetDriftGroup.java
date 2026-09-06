package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.actual.ActualRuleset;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.RulesetRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Repository rulesets. The comparison and the request body live in
 * {@link RulesetComparison}, which the organization group shares; this class
 * owns the repository endpoints and the ref-name conditions. Push rulesets are
 * the same group on the same endpoints, with no conditions.
 */
public class RulesetDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, ? extends Drifty.Ruleset> desired;
	private final List<ActualRuleset> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public RulesetDriftGroup(
			Map<String, ? extends Drifty.Ruleset> desired,
			List<ActualRuleset> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desired = Map.copyOf(desired);
		this.actual = List.copyOf(actual);
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.RULESETS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		if (desired.isEmpty() && actual.isEmpty()) {
			return fixes;
		}

		if (desired.isEmpty()) {
			actual.stream().map(this::deleteExtraFix).forEach(fixes::add);
			return fixes;
		}

		Map<String, ActualRuleset> actualByName = actual.stream()
				.collect(
						Collectors
								.toMap(ActualRuleset::name, r -> r, (a, _) -> a)
				);

		for (var entry : desired.entrySet()) {
			String rName = entry.getKey();
			Drifty.Ruleset wanted = entry.getValue();
			ActualRuleset got = actualByName.get(rName);

			if (got == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(rName),
								() -> {
									client.createRuleset(
											owner,
											repo,
											request(rName, wanted)
									);
									return FixResult.success();
								}
						)
				);
				continue;
			}

			var items = RulesetComparison.compare(rName, wanted, got);
			if (!items.isEmpty()) {
				final var gotId = got.id();
				fixes.add(new DriftFix(items, () -> {
					client.updateRuleset(
							owner,
							repo,
							gotId,
							request(rName, wanted)
					);
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

	private DriftFix deleteExtraFix(ActualRuleset extra) {
		return new DriftFix(new DriftItem.SectionExtra(extra.name()), () -> {
			client.deleteRuleset(owner, repo, extra.id());
			return FixResult.success();
		});
	}

	/**
	 * A branch or tag ruleset carries its ref-name condition; a push ruleset
	 * carries no conditions at all, and the field is left out of the body.
	 */
	private static RulesetRequest request(String name, Drifty.Ruleset args) {
		var refName = RulesetComparison.refName(args);
		return RulesetComparison.request(
				name,
				args,
				refName == null ? null
						: new RulesetRequest.Conditions(
								refName,
								null,
								null,
								null
						)
		);
	}

}
