package io.github.arlol.githubcheck.drift;

import java.util.List;
import java.util.Optional;

import io.github.arlol.githubcheck.PklTypes;
import io.github.arlol.githubcheck.actual.ActualPages;
import io.github.arlol.githubcheck.client.GitHubClient;
import io.github.arlol.githubcheck.client.RepoRef;
import io.github.arlol.githubcheck.client.PagesCreateRequest;
import io.github.arlol.githubcheck.client.PagesUpdateRequest;
import io.github.arlol.githubcheck.pkl.Drifty;

public class PagesDriftGroup extends DriftGroup {

	private static final String BUILD_TYPE_LEGACY = "legacy";

	private final boolean desiredEnabled;
	private final Drifty.Pages desired;
	private final Optional<ActualPages> actual;
	private final GitHubClient client;
	private final String owner;
	private final String repo;

	public PagesDriftGroup(
			Drifty.Pages desired,
			Optional<ActualPages> actual,
			GitHubClient client,
			RepoRef ref
	) {
		this.desiredEnabled = desired != null;
		this.desired = desired;
		this.actual = actual;
		this.client = client;
		this.owner = ref.owner();
		this.repo = ref.name();
	}

	@Override
	public Drifty.GroupName name() {
		return Drifty.GroupName.PAGES;
	}

	@Override
	protected List<DriftFix> detectDrift() {
		if (!desiredEnabled) {
			return List.of();
		}

		if (actual.isEmpty()) {
			return List
					.of(new DriftFix(new DriftItem.SectionMissing(""), () -> {
						client.createPages(
								owner,
								repo,
								buildPagesCreateRequest(desired)
						);
						return FixResult.success();
					}));
		}

		ActualPages p = actual.orElseThrow();

		var items = combine(
				compare("build_type", desired.buildType, p.buildType()),
				BUILD_TYPE_LEGACY.equals(desired.buildType)
						&& p.source().isPresent()
								? combine(
										compare(
												"source.branch",
												desired.sourceBranch,
												p.source()
														.orElseThrow()
														.branch()
										),
										compare(
												"source.path",
												desired.sourcePath,
												p.source().orElseThrow().path()
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

	private static PagesCreateRequest buildPagesCreateRequest(
			Drifty.Pages args
	) {
		PagesCreateRequest.Source source = null;
		if (BUILD_TYPE_LEGACY.equals(args.buildType)) {
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
		if (BUILD_TYPE_LEGACY.equals(args.buildType)) {
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
