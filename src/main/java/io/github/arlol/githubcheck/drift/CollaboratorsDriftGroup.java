package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualCollaborators;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Who has direct access to a repository. A missing or drifted collaborator is
 * one PUT, which invites a user who is not yet in and is idempotent for one who
 * is; a pending invitation appears in no listing, so the entry stays missing
 * until it is accepted. Team access is written through the organization's team
 * endpoint, which is why a repository under a personal account cannot name a
 * team. Anyone on GitHub the config does not list is reported and left in
 * place.
 */
public class CollaboratorsDriftGroup extends DriftGroup<Drifty.GroupName> {

	private final Map<String, Drifty.CollaboratorPermission> users;
	private final Map<String, Drifty.CollaboratorPermission> teams;
	private final ActualCollaborators actual;
	private final boolean organizationOwned;
	private final GitHubClient client;
	private final RepoRef ref;

	public CollaboratorsDriftGroup(
			Map<String, Drifty.CollaboratorPermission> users,
			Map<String, Drifty.CollaboratorPermission> teams,
			ActualCollaborators actual,
			boolean organizationOwned,
			GitHubClient client,
			RepoRef ref
	) {
		this.users = Collections.unmodifiableMap(new LinkedHashMap<>(users));
		this.teams = Collections.unmodifiableMap(new LinkedHashMap<>(teams));
		this.actual = actual == null
				? new ActualCollaborators(Map.of(), Map.of())
				: actual;
		this.organizationOwned = organizationOwned;
		this.client = client;
		this.ref = ref;
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.COLLABORATORS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : users.entrySet()) {
			String login = entry.getKey();
			String wanted = entry.getValue().toString();
			String got = actual.users().get(login);
			DriftItem item = got == null ? new DriftItem.SectionMissing(login)
					: ocompare(login, wanted, got).orElse(null);
			if (item != null) {
				fixes.add(new DriftFix(item, () -> {
					client.addCollaborator(
							ref.owner(),
							ref.name(),
							login,
							wanted
					);
					return FixResult.success();
				}));
			}
		}
		for (String login : actual.users().keySet()) {
			if (!users.containsKey(login)) {
				fixes.add(reported(login, "collaborators"));
			}
		}

		for (var entry : teams.entrySet()) {
			String slug = entry.getKey();
			String wanted = entry.getValue().toString();
			String path = "teams." + slug;
			if (!organizationOwned) {
				var item = new DriftItem.FieldMismatch(path, wanted, null);
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"a repository under a personal account cannot grant a team access"
								)
						)
				);
				continue;
			}
			String got = actual.teams().get(slug);
			DriftItem item = got == null ? new DriftItem.SectionMissing(path)
					: ocompare(path, wanted, got).orElse(null);
			if (item != null) {
				fixes.add(new DriftFix(item, () -> {
					client.setTeamRepositoryPermission(
							ref.owner(),
							slug,
							ref.owner(),
							ref.name(),
							wanted
					);
					return FixResult.success();
				}));
			}
		}
		for (String slug : actual.teams().keySet()) {
			if (!teams.containsKey(slug)) {
				fixes.add(reported("teams." + slug, "teams"));
			}
		}

		return fixes;
	}

	private static DriftFix reported(String path, String what) {
		var item = new DriftItem.SectionExtra(path);
		return new DriftFix(
				item,
				() -> FixResult.unfixed(
						item,
						"drifty does not remove " + what + " it did not add"
				)
		);
	}

}
