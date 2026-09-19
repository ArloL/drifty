package io.github.arlol.githubcheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

import org.pkl.config.java.ConfigEvaluatorBuilder;
import org.pkl.core.module.ModuleKey;
import org.pkl.core.module.ModuleKeyFactory;
import org.pkl.core.module.ModuleKeys;

/**
 * The schema, answered from inside the binary rather than fetched from GitHub.
 * <p>
 * Every exported config {@code amends} {@link #MAIN_SCHEMA_URI}, and Pkl's
 * cache under {@code ~/.pkl/cache} holds packages only — a plain {@code https}
 * module is fetched again on every evaluation. Measured 2026-09-19 on a
 * 477-line config: 0.21 s evaluated against the URL, 0.07 s against a local
 * copy of the same file, which was the whole of drifty's config-loading phase.
 * <p>
 * Answering with the shipped copy is also the more correct of the two. The
 * generated {@code Drifty} records come from {@code config/drifty.pkl} at build
 * time, so a binary that resolved main's newer schema could be handed a field
 * its own mapper has never heard of.
 * <p>
 * Only the main-branch URL is answered. A config pinned to a tag or a commit
 * names a schema this binary does not carry, and falls through to Pkl's own
 * {@code https} module key.
 */
public final class BundledSchema {

	public static final String MAIN_SCHEMA_URI = "https://raw.githubusercontent.com/ArloL/drifty/refs/heads/main/config/drifty.pkl";

	private static final URI MAIN_SCHEMA = URI.create(MAIN_SCHEMA_URI);

	/**
	 * The schema beside this class, copied out of {@code config/} by the build
	 * — see the {@code pkl-add-resource} execution in {@code pom.xml}.
	 */
	private static final String RESOURCE = "drifty.pkl";

	private BundledSchema() {
	}

	/**
	 * The builder every evaluation of a config or of the schema starts from,
	 * with {@link #moduleKeyFactory()} ahead of the factories Pkl preconfigures
	 * — prepended, not added, because the first factory that claims a URI is
	 * the one that answers it.
	 */
	public static ConfigEvaluatorBuilder evaluatorBuilder() {
		var builder = ConfigEvaluatorBuilder.preconfigured();
		var evaluator = builder.getEvaluatorBuilder();
		var factories = new ArrayList<ModuleKeyFactory>();
		factories.add(moduleKeyFactory());
		factories.addAll(evaluator.getModuleKeyFactories());
		evaluator.setModuleKeyFactories(factories);
		return builder;
	}

	/**
	 * Claims {@link #MAIN_SCHEMA_URI} and nothing else. Has to be the first
	 * factory the evaluator consults, or Pkl's own {@code https} factory
	 * answers the URI first and sends the request this exists to avoid.
	 */
	static ModuleKeyFactory moduleKeyFactory() {
		return uri -> MAIN_SCHEMA.equals(uri)
				? Optional.of(ModuleKeys.synthetic(MAIN_SCHEMA, source()))
				: Optional.<ModuleKey>empty();
	}

	static String source() {
		try (var in = BundledSchema.class.getResourceAsStream(RESOURCE)) {
			return new String(
					Objects.requireNonNull(in, RESOURCE).readAllBytes(),
					StandardCharsets.UTF_8
			);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
