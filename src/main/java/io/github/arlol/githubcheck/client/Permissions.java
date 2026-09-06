package io.github.arlol.githubcheck.client;

/**
 * The permission booleans GitHub attaches to a collaborator or a team in a
 * repository context. A higher level implies the lower ones, so the level is
 * the highest that is on.
 */
public record Permissions(
		Boolean pull,
		Boolean triage,
		Boolean push,
		Boolean maintain,
		Boolean admin
) {

	/** The highest level that is on, in the config's vocabulary. */
	public String level() {
		if (Boolean.TRUE.equals(admin)) {
			return "admin";
		}
		if (Boolean.TRUE.equals(maintain)) {
			return "maintain";
		}
		if (Boolean.TRUE.equals(push)) {
			return "push";
		}
		if (Boolean.TRUE.equals(triage)) {
			return "triage";
		}
		return "pull";
	}

}
