package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.TeamCreateRequest;
import io.github.arlol.githubcheck.client.TeamRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Teams on an organization, matched by slug. A missing team is POSTed with its
 * name and then given its members; an existing one's settings go to one PATCH
 * and each role's missing members to their own fix, one membership PUT per
 * login. Members on GitHub the config does not list, and teams the config does
 * not declare, are reported and left alone. A parent is named by slug and
 * resolved to the id GitHub wants from the teams already read, or with one
 * request when the parent was created in the same run.
 */
public class OrgTeamsDriftGroup extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.Team> desired;
	private final Map<String, ActualTeam> actual;
	private final GitHubClient client;
	private final String org;

	public OrgTeamsDriftGroup(
			Map<String, Drifty.Team> desired,
			List<ActualTeam> actual,
			GitHubClient client,
			String org
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		var bySlug = new LinkedHashMap<String, ActualTeam>();
		for (ActualTeam team : actual) {
			bySlug.put(team.slug(), team);
		}
		this.actual = Collections.unmodifiableMap(bySlug);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_TEAMS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String slug = entry.getKey();
			Drifty.Team wanted = entry.getValue();
			ActualTeam current = actual.get(slug);
			if (current == null) {
				fixes.add(
						new DriftFix(
								new DriftItem.SectionMissing(slug),
								() -> create(slug, wanted)
						)
				);
				continue;
			}
			fixes.add(
					new DriftFix(compareSettings(slug, wanted, current), () -> {
						client.updateTeam(org, slug, request(slug, wanted));
						return FixResult.success();
					})
			);
			fixes.addAll(
					compareRole(
							slug,
							"members",
							"member",
							wanted.members,
							current.members()
					)
			);
			fixes.addAll(
					compareRole(
							slug,
							"maintainers",
							"maintainer",
							wanted.maintainers,
							current.maintainers()
					)
			);
		}

		for (ActualTeam team : actual.values()) {
			if (!desired.containsKey(team.slug())) {
				var item = new DriftItem.SectionExtra(team.slug());
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not delete teams it did not create"
								)
						)
				);
			}
		}

		return fixes;
	}

	private static List<DriftItem> compareSettings(
			String slug,
			Drifty.Team wanted,
			ActualTeam current
	) {
		var items = new ArrayList<DriftItem>();
		if (wanted.name != null) {
			items.addAll(compare(slug + ".name", wanted.name, current.name()));
		}
		items.addAll(
				combine(
						compare(
								slug + ".description",
								wanted.description,
								current.description()
						),
						compare(
								slug + ".privacy",
								wanted.privacy.toString(),
								current.privacy()
						),
						compare(
								slug + ".notification_setting",
								wanted.notificationSetting.toString(),
								current.notificationSetting()
						),
						compare(
								slug + ".parent",
								wanted.parent,
								current.parent()
						)
				)
		);
		return items;
	}

	/**
	 * Missing logins of one role are one fix; each login GitHub has in the role
	 * that the config does not list is its own reported-only item.
	 */
	private List<DriftFix> compareRole(
			String slug,
			String field,
			String role,
			List<String> wanted,
			Set<String> current
	) {
		var fixes = new ArrayList<DriftFix>();
		Set<String> missing = new HashSet<>(wanted);
		missing.removeAll(current);
		if (!missing.isEmpty()) {
			fixes.add(
					new DriftFix(
							new DriftItem.SetDrift(
									slug + "." + field,
									missing,
									Set.of()
							),
							() -> {
								for (String login : missing) {
									client.setTeamMembership(
											org,
											slug,
											login,
											role
									);
								}
								return FixResult.success();
							}
					)
			);
		}
		for (String login : current) {
			if (!wanted.contains(login)) {
				var item = new DriftItem.SectionExtra(
						slug + "." + field + "." + login
				);
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not remove team members it did not add"
								)
						)
				);
			}
		}
		return fixes;
	}

	private FixResult create(String slug, Drifty.Team wanted) {
		var created = client
				.createTeam(org, TeamCreateRequest.from(request(slug, wanted)));
		for (String login : wanted.members) {
			client.setTeamMembership(org, created.slug(), login, "member");
		}
		for (String login : wanted.maintainers) {
			client.setTeamMembership(org, created.slug(), login, "maintainer");
		}
		return FixResult.success();
	}

	private TeamRequest request(String slug, Drifty.Team wanted) {
		return new TeamRequest(
				wanted.name == null ? slug : wanted.name,
				wanted.description,
				wanted.privacy.toString(),
				wanted.notificationSetting.toString(),
				wanted.parent == null ? null : parentId(wanted.parent)
		);
	}

	private Long parentId(String parentSlug) {
		Map<String, Long> ids = new HashMap<>();
		for (ActualTeam team : actual.values()) {
			ids.put(team.slug(), team.id());
		}
		Long id = ids.get(parentSlug);
		return id != null ? id : client.getTeamId(org, parentSlug);
	}

}
