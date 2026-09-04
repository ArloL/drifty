package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import org.pkl.config.java.ConfigEvaluator;
import org.pkl.config.java.mapper.Types;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * Reads a config file into {@link DriftyConfig}.
 * <p>
 * Both account mappings are asked for as {@link LinkedHashMap}, not as
 * {@code Map}: Pkl's mapper instantiates the raw target class, and its default
 * for {@code Map} is a {@code HashMap}, which loses the order the config
 * declares accounts in. That order is what the progress lines and the
 * missing-secrets list are printed in.
 */
public final class PklConfigLoader {

	private PklConfigLoader() {
	}

	public static DriftyConfig load(Path pklFile) throws IOException {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			var root = evaluator.evaluate(ModuleSource.path(pklFile));
			return new DriftyConfig(
					root.get("organizations")
							.as(
									Types.parameterizedType(
											LinkedHashMap.class,
											String.class,
											Drifty.Organization.class
									)
							),
					root.get("users")
							.as(
									Types.parameterizedType(
											LinkedHashMap.class,
											String.class,
											Drifty.User.class
									)
							)
			);
		}
	}

}
