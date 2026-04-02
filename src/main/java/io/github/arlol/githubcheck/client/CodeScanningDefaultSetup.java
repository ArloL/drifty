package io.github.arlol.githubcheck.client;

public record CodeScanningDefaultSetup(
		String state
) {

	public boolean isEnabled() {
		return !"not-configured".equals(state);
	}

}
