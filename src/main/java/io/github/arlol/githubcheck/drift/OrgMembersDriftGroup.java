package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.actual.ActualOrgMember;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Organization members and their roles. A login missing from GitHub is reported
 * missing and fixed with the membership PUT, which invites the user; a pending
 * invitation is in no listing, so the entry stays missing until it is accepted.
 * Members the config does not list are reported and never removed.
 */
public class OrgMembersDriftGroup extends DriftGroup<Drifty.OrgGroupName> {

	private final Map<String, Drifty.OrgRole> desired;
	private final Map<String, String> actual;
	private final GitHubClient client;
	private final String org;

	public OrgMembersDriftGroup(
			Map<String, Drifty.OrgRole> desired,
			List<ActualOrgMember> actual,
			GitHubClient client,
			String org
	) {
		this.desired = Collections
				.unmodifiableMap(new LinkedHashMap<>(desired));
		var byLogin = new LinkedHashMap<String, String>();
		for (ActualOrgMember member : actual) {
			byLogin.put(member.login(), member.role());
		}
		this.actual = Collections.unmodifiableMap(byLogin);
		this.client = client;
		this.org = org;
	}

	@Override
	public Drifty.OrgGroupName name() {
		return Drifty.OrgGroupName.ORG_MEMBERS;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		var fixes = new ArrayList<DriftFix>();

		for (var entry : desired.entrySet()) {
			String login = entry.getKey();
			String wanted = entry.getValue().toString();
			String got = actual.get(login);
			DriftItem item = got == null ? new DriftItem.SectionMissing(login)
					: ocompare(login, wanted, got).orElse(null);
			if (item != null) {
				fixes.add(new DriftFix(item, () -> {
					client.setOrgMembership(org, login, wanted);
					return FixResult.success();
				}));
			}
		}

		for (String login : actual.keySet()) {
			if (!desired.containsKey(login)) {
				var item = new DriftItem.SectionExtra(login);
				fixes.add(
						new DriftFix(
								item,
								() -> FixResult.unfixed(
										item,
										"drifty does not remove members it did not add"
								)
						)
				);
			}
		}

		return fixes;
	}

}
