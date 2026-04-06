package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.config.PagesArgs;
import io.github.arlol.githubcheck.config.RepositoryArgs;

public class PagesDriftGroup extends DriftGroup {

	private final boolean desiredEnabled;
	private final PagesArgs desired;
	private final Optional<PagesResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public PagesDriftGroup(
			RepositoryArgs desired,
			Optional<PagesResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desiredEnabled = desired.pages();
		this.desired = desiredEnabled ? desired.pagesArgs() : null;
		this.actual = actual;
		this.client = client;
		this.owner = owner;
		this.repo = repo;
	}

	@Override
	public String name() {
		return "pages";
	}

	@Override
	public List<DriftItem> detect() {
		var items = new ArrayList<DriftItem>();

		if (!desiredEnabled) {
			return items;
		}

		if (actual.isEmpty()) {
			items.add(new DriftItem.SectionMissing("pages"));
			return items;
		}

		PagesResponse p = actual.orElseThrow();

		items.addAll(
				compare(
						"build_type",
						desired.buildType().name().toLowerCase(Locale.ROOT),
						p.buildType() != null
								? p.buildType().name().toLowerCase(Locale.ROOT)
								: null
				)
		);

		if (desired.buildType() == PagesBuildType.LEGACY
				&& p.source() != null) {
			items.addAll(
					compare(
							"source.branch",
							desired.sourceBranch(),
							p.source().branch()
					)
			);
			items.addAll(
					compare(
							"source.path",
							desired.sourcePath(),
							p.source().path()
					)
			);
		}

		items.addAll(compare("https_enforced", true, p.httpsEnforced()));

		return items;
	}

	@Override
	public FixResult fix() {
		if (actual.isEmpty()) {
			client.createPages(owner, repo, buildPagesCreateRequest(desired));
		} else {
			client.updatePages(owner, repo, buildPagesUpdateRequest(desired));
		}
		return FixResult.success();
	}

	private static io.github.arlol.githubcheck.client.PagesCreateRequest buildPagesCreateRequest(
			PagesArgs args
	) {
		io.github.arlol.githubcheck.client.PagesCreateRequest.Source source = null;
		if (args.buildType() == PagesBuildType.LEGACY) {
			source = new io.github.arlol.githubcheck.client.PagesCreateRequest.Source(
					args.sourceBranch(),
					args.sourcePath()
			);
		}
		return new io.github.arlol.githubcheck.client.PagesCreateRequest(
				args.buildType(),
				source
		);
	}

	private static io.github.arlol.githubcheck.client.PagesUpdateRequest buildPagesUpdateRequest(
			PagesArgs args
	) {
		io.github.arlol.githubcheck.client.PagesUpdateRequest.Source source = null;
		if (args.buildType() == PagesBuildType.LEGACY) {
			source = new io.github.arlol.githubcheck.client.PagesUpdateRequest.Source(
					args.sourceBranch(),
					args.sourcePath()
			);
		}
		return new io.github.arlol.githubcheck.client.PagesUpdateRequest(
				args.buildType(),
				source,
				true
		);
	}

}
