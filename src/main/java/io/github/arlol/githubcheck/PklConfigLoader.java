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

	public static List<Drifty.Repository> load(Path pklFile)
			throws IOException {
		try (var evaluator = ConfigEvaluator.preconfigured()) {
			var root = evaluator.evaluate(ModuleSource.path(pklFile));
			return root.get("repositories")
					.as(Types.listOf(Drifty.Repository.class));
		}
	}

}
