package io.github.arlol.githubcheck.config;

import io.github.arlol.githubcheck.client.PagesResponse;

public final class PagesArgs {

	private final PagesResponse.BuildType buildType;
	private final String sourceBranch;
	private final String sourcePath;

	private PagesArgs(
			PagesResponse.BuildType buildType,
			String sourceBranch,
			String sourcePath
	) {
		this.buildType = buildType;
		this.sourceBranch = sourceBranch;
		this.sourcePath = sourcePath;
	}

	public static PagesArgs workflow() {
		return new PagesArgs(PagesResponse.BuildType.WORKFLOW, null, null);
	}

	public static PagesArgs legacy(String sourceBranch, String sourcePath) {
		return new PagesArgs(
				PagesResponse.BuildType.LEGACY,
				sourceBranch,
				sourcePath
		);
	}

	public PagesResponse.BuildType buildType() {
		return buildType;
	}

	public String sourceBranch() {
		return sourceBranch;
	}

	public String sourcePath() {
		return sourcePath;
	}

}
