package io.github.arlol.githubcheck.config;

import io.github.arlol.githubcheck.client.PagesBuildType;

public final class PagesArgs {

	private final PagesBuildType buildType;
	private final String sourceBranch;
	private final String sourcePath;

	private PagesArgs(
			PagesBuildType buildType,
			String sourceBranch,
			String sourcePath
	) {
		this.buildType = buildType;
		this.sourceBranch = sourceBranch;
		this.sourcePath = sourcePath;
	}

	public static PagesArgs workflow() {
		return new PagesArgs(PagesBuildType.WORKFLOW, null, null);
	}

	public static PagesArgs legacy(String sourceBranch, String sourcePath) {
		return new PagesArgs(PagesBuildType.LEGACY, sourceBranch, sourcePath);
	}

	public PagesBuildType buildType() {
		return buildType;
	}

	public String sourceBranch() {
		return sourceBranch;
	}

	public String sourcePath() {
		return sourcePath;
	}

}
