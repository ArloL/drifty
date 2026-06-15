package io.github.arlol.githubcheck.drift;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesCreateRequest;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.PagesUpdateRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

public class PagesDriftGroup extends DriftGroup {

	private final boolean desiredEnabled;
	private final Drifty.Pages desired;
	private final Optional<PagesResponse> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public PagesDriftGroup(
			Drifty.Repository desired,
			Optional<PagesResponse> actual,
			GitHubClient client,
			String owner,
			String repo
	) {
		this.desiredEnabled = desired.pages != null;
		this.desired = desiredEnabled ? desired.pages : null;
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
	public List<DriftFix> detect() {
		if (!desiredEnabled) {
			return List.of();
		}

		if (actual.isEmpty()) {
			return List.of(
					new DriftFix(new DriftItem.SectionMissing("pages"), () -> {
						client.createPages(
								owner,
								repo,
								buildPagesCreateRequest(desired)
						);
						return FixResult.success();
					})
			);
		}

		PagesResponse p = actual.orElseThrow();

		var items = combine(
				compare(
						"build_type",
						desired.buildType,
						p.buildType() != null
								? p.buildType().name().toLowerCase(Locale.ROOT)
								: null
				),
				"legacy".equals(desired.buildType) && p.source() != null
						? combine(
								compare(
										"source.branch",
										desired.sourceBranch,
										p.source().branch()
								),
								compare(
										"source.path",
										desired.sourcePath,
										p.source().path()
								)
						) : List.of(),
				compare("https_enforced", true, p.httpsEnforced())
		);
		return List.of(new DriftFix(items, () -> {
			client.updatePages(owner, repo, buildPagesUpdateRequest(desired));
			return FixResult.success();
		}));
	}

	private static PagesCreateRequest buildPagesCreateRequest(
			Drifty.Pages args
	) {
		PagesCreateRequest.Source source = null;
		if ("legacy".equals(args.buildType)) {
			source = new PagesCreateRequest.Source(
					args.sourceBranch,
					args.sourcePath
			);
		}
		return new PagesCreateRequest(
				PklTypes.pagesBuildType(args.buildType),
				source
		);
	}

	private static PagesUpdateRequest buildPagesUpdateRequest(
			Drifty.Pages args
	) {
		PagesUpdateRequest.Source source = null;
		if ("legacy".equals(args.buildType)) {
			source = new PagesUpdateRequest.Source(
					args.sourceBranch,
					args.sourcePath
			);
		}
		return new PagesUpdateRequest(
				PklTypes.pagesBuildType(args.buildType),
				source,
				true
		);
	}

}
