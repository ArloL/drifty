package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.Optional;

import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A GitHub Pages site, as the config lines that differ from the schema's
 * defaults.
 * <p>
 * The caller writes the returned node under {@code pages} only when
 * {@code RepositoryState.pages()} is present — the field's own schema default
 * is {@code null}, so a site's mere existence is already the drift from that,
 * the way an empty {@code pullRequest} still means "required" in
 * {@code RulesetExporter}. {@link ActualPages#httpsEnforced()} has no schema
 * field to compare it against — GitHub has enforced HTTPS on every Pages site
 * since 2018 and the config has no way to ask for anything else — so it is read
 * by nothing here.
 */
public final class PagesExporter {

	private PagesExporter() {
	}

	public static PklNode node(ActualPages actual, Drifty.Pages base) {
		var members = new ArrayList<>(
				Fields.members(buildType(actual.buildType(), base.buildType))
		);
		actual.source().ifPresent(source -> {
			members.addAll(
					Fields.members(
							Fields.field(
									"sourceBranch",
									source.branch(),
									base.sourceBranch
							),
							Fields.field(
									"sourcePath",
									source.path(),
									base.sourcePath
							)
					)
			);
		});
		return new PklNode.Obj(members);
	}

	/**
	 * {@code buildType} is a non-nullable union on the schema side, but GitHub
	 * reports no build type at all for a site old enough to predate the concept
	 * — writing {@code null} there would produce a file the schema refuses to
	 * load, so that case is left at the schema's own default instead of forcing
	 * a value onto a field the schema cannot hold null in.
	 */
	private static Optional<PklNode.Member> buildType(
			String actual,
			String base
	) {
		if (actual == null) {
			return Optional.empty();
		}
		return Fields.field("buildType", actual, base);
	}

}
