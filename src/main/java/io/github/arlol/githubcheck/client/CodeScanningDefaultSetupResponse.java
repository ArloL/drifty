package io.github.arlol.githubcheck.client;

public record CodeScanningDefaultSetupResponse(
		String state
) {

	public boolean isEnabled() {
		return !"not-configured".equals(state);
	}

}
