package io.github.arlol.githubcheck.client;

/**
 * Identifies one repository on GitHub.
 * <p>
 * Owner and name travel together because a repository name is only unique
 * within an owner. Passing them as one value rather than two adjacent strings
 * also removes the argument-order hazard: two {@code String} parameters of the
 * same type can be swapped at a call site and still compile.
 *
 * @param owner the organisation or user account
 * @param name  the repository name within that account
 */
public record RepoRef(
		String owner,
		String name
) {

	@Override
	public String toString() {
		return owner + "/" + name;
	}

}
