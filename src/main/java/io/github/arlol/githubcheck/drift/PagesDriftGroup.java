package io.github.arlol.githubcheck.drift;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.PagesBuildType;
import io.github.arlol.githubcheck.client.PagesCreateRequest;
import io.github.arlol.githubcheck.client.PagesResponse;
import io.github.arlol.githubcheck.client.PagesUpdateRequest;
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
						desired.buildType().name().toLowerCase(Locale.ROOT),
						p.buildType() != null
								? p.buildType().name().toLowerCase(Locale.ROOT)
								: null
				),
				desired.buildType() == PagesBuildType.LEGACY
						&& p.source() != null
								? combine(
										compare(
												"source.branch",
												desired.sourceBranch(),
												p.source().branch()
										),
										compare(
												"source.path",
												desired.sourcePath(),
												p.source().path()
										)
								)
								: List.of(),
				compare("https_enforced", true, p.httpsEnforced())
		);
		return List.of(new DriftFix(items, () -> {
			client.updatePages(owner, repo, buildPagesUpdateRequest(desired));
			return FixResult.success();
		}));
	}

	private static PagesCreateRequest buildPagesCreateRequest(PagesArgs args) {
		PagesCreateRequest.Source source = null;
		if (args.buildType() == PagesBuildType.LEGACY) {
			source = new PagesCreateRequest.Source(
					args.sourceBranch(),
					args.sourcePath()
			);
		}
		return new PagesCreateRequest(args.buildType(), source);
	}

	private static PagesUpdateRequest buildPagesUpdateRequest(PagesArgs args) {
		PagesUpdateRequest.Source source = null;
		if (args.buildType() == PagesBuildType.LEGACY) {
			source = new PagesUpdateRequest.Source(
					args.sourceBranch(),
					args.sourcePath()
			);
		}
		return new PagesUpdateRequest(args.buildType(), source, true);
	}

}
