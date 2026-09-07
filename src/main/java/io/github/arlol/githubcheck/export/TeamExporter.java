package io.github.arlol.githubcheck.export;

import java.util.List;

import io.github.arlol.githubcheck.actual.ActualTeam;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A team, as the config lines that differ from the schema's defaults.
 * <p>
 * {@code name} has no plain schema default to diff against — the schema
 * documents it as defaulting to the slug the entry is keyed by, so the
 * comparison here is against {@code actual.slug()} rather than
 * {@code defaults.team().name} (which is simply {@code null}, and would make
 * every export carry a name), the same normalization {@code OrgTeamsDriftGroup}
 * applies before comparing.
 */
public final class TeamExporter {

	private TeamExporter() {
	}

	public static PklNode.Member entry(
			ActualTeam actual,
			Drifty.Team defaults
	) {
		List<PklNode.Member> members = Fields.members(
				Fields.field("name", actual.name(), actual.slug()),
				Fields.field(
						"description",
						actual.description(),
						defaults.description
				),
				Fields.field(
						"privacy",
						actual.privacy(),
						defaults.privacy.toString()
				),
				Fields.field(
						"notificationSetting",
						actual.notificationSetting(),
						defaults.notificationSetting.toString()
				),
				Fields.field("parent", actual.parent(), defaults.parent),
				Fields.strings("members", actual.members(), defaults.members),
				Fields.strings(
						"maintainers",
						actual.maintainers(),
						defaults.maintainers
				)
		);
		return new PklNode.Field(actual.slug(), new PklNode.Obj(members));
	}

}
