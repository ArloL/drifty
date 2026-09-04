package io.github.arlol.githubcheck;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * The whole desired state: organizations and personal accounts, each holding
 * the repositories it owns. The owner is the key, so a repository cannot name
 * an account nobody declared.
 * <p>
 * Both maps keep the order the config declares them in. {@code Map.copyOf}
 * would not: its iteration order is salted per JVM run, so the progress lines
 * and the missing-secrets list would come out in a different order on every run
 * of the same config.
 */
public record DriftyConfig(
		Map<String, Drifty.Organization> organizations,
		Map<String, Drifty.User> users
) {

	public DriftyConfig {
		organizations = Collections
				.unmodifiableMap(new LinkedHashMap<>(organizations));
		users = Collections.unmodifiableMap(new LinkedHashMap<>(users));
	}

	/** Every declared repository, for checks that do not care who owns it. */
	public List<Drifty.Repository> allRepositories() {
		return Stream.concat(
				organizations.values()
						.stream()
						.flatMap(o -> o.repositories.stream()),
				users.values().stream().flatMap(u -> u.repositories.stream())
		).toList();
	}

}
