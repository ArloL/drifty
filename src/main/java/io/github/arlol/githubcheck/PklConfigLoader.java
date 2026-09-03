package io.github.arlol.githubcheck;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.pkl.config.java.ConfigEvaluator;
import org.pkl.config.java.mapper.Types;
import org.pkl.core.ModuleSource;

import io.github.arlol.githubcheck.pkl.Drifty;

public final class PklConfigLoader {

	private PklConfigLoader() {
	}

	/**
	 * Loads the repositories a config declares, each carrying the account it
	 * lives under.
	 * <p>
	 * A config names its account once, in the module-level {@code organisation}
	 * property, and a repository only sets {@code owner} to reach a different
	 * account. Filling the null ones in happens here rather than in the schema
	 * because Pkl allows a class body to reference a module property only when
	 * that property is {@code const}, and a {@code const} property cannot be
	 * assigned by the config that amends the schema.
	 */
	public static List<Drifty.Repository> load(Path pklFile)
			throws IOException {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			var root = evaluator.evaluate(ModuleSource.path(pklFile));
			String organisation = root.get("organisation").as(String.class);
			List<Drifty.Repository> repositories = root.get("repositories")
					.as(Types.listOf(Drifty.Repository.class));
			return repositories.stream()
					.map(
							repo -> repo.owner != null ? repo
									: repo.withOwner(organisation)
					)
					.toList();
		}
	}

}
