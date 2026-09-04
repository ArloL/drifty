package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pkl.config.java.Config;
import org.pkl.config.java.ConfigEvaluator;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Reads a config file into {@link DriftyConfig}.
 * <p>
 * Both account mappings are walked key by key instead of being mapped in one
 * {@code as(Map...)} call, because neither map type Pkl's mapper would use
 * works here. Asked for a {@code Map}, it instantiates a {@code HashMap}, which
 * loses the order the config declares accounts in — the order the progress
 * lines and the missing-secrets list are printed in. Asked for a
 * {@code LinkedHashMap}, it fails outright in the native image:
 * {@code PMapToMap} instantiates the raw target class reflectively, and
 * {@code LinkedHashMap} is not one of the collection types the
 * pkl-config-java-native metadata registers, so the lookup comes back empty and
 * the mapper reports "no conversion was found" instead of loading the config.
 * Building the map here needs no metadata beyond the record types themselves,
 * and keeps the declaration order Pkl evaluated the mapping in.
 */
public final class PklConfigLoader {

	private PklConfigLoader() {
	}

	public static DriftyConfig load(Path pklFile) throws IOException {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			var root = evaluator.evaluate(ModuleSource.path(pklFile));
			return new DriftyConfig(
					byLogin(
							root.get("organizations"),
							Drifty.Organization.class
					),
					byLogin(root.get("users"), Drifty.User.class)
			);
		}
	}

	private static <T> Map<String, T> byLogin(Config accounts, Class<T> type) {
		var byLogin = new LinkedHashMap<String, T>();
		for (Object login : ((Map<?, ?>) accounts.getRawValue()).keySet()) {
			String key = (String) login;
			byLogin.put(key, accounts.get(key).as(type));
		}
		return byLogin;
	}

}
