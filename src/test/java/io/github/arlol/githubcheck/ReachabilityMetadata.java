package io.github.arlol.githubcheck;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Dev tool that turns a native-image tracing-agent dump into the two
 * scope-split reachability metadata files the project commits.
 *
 * <p>
 * The agent records every reflective access made while the JUnit + WireMock
 * test suite runs. The vast majority belong to test-only infrastructure or to
 * libraries and the JDK whose metadata the GraalVM reachability-metadata
 * repository already supplies. Caller-based access filters cannot remove them:
 * the reflective calls into {@code sun.security.*},
 * {@code com.sun.management.*}, etc. are made by JDK/JSSE code, not by the test
 * library, so filtering by the calling class never matches. Instead this tool
 * partitions the agent output by the target type/resource.
 *
 * <p>
 * Reflection uses a production <em>allowlist</em>: only the project's own types
 * and the lazysodium/JNA binding (whose JNI registration is not in the metadata
 * repository) belong in the shipped image. Everything else is routed to
 * {@code src/test/resources}. This was established empirically — the production
 * native image builds, loads Pkl config and reaches GitHub over TLS with only
 * the allowlisted reflection, and the full native test suite (Jackson
 * round-trips + libsodium crypto) passes — so the ~160 third-party/JDK entries
 * the agent recorded are all repository-supplied.
 *
 * <p>
 * Resources use the same allowlist idea: only Pkl's own resources (mapper
 * configs, stdlib, ServiceLoader files) and the {@code libsodium}/
 * {@code jnidispatch} native libraries survive into the production image — they
 * are the resources the metadata repository does not supply. The native test
 * image puts both resource roots on its classpath, so it still sees the union;
 * the production image sees only the trimmed file.
 *
 * <p>
 * After splitting, the production file is augmented with every public record in
 * the {@code client} and {@code pkl} packages (via ClassGraph) so the project's
 * own Jackson/Pkl types are always registered, even if no test happened to
 * exercise them.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * ./mvnw test -Dagent=true               # retrace into target/native/agent-output
 * ./mvnw test-compile                    # compile this tool onto the test classpath
 * ./mvnw exec:java@reachability-metadata # split into the two scoped files
 * </pre>
 */
public final class ReachabilityMetadata {

	private static final Path INPUT = Path
			.of("target/native/agent-output/test/reachability-metadata.json");
	private static final Path MAIN_OUT = Path.of(
			"src/main/resources/META-INF/native-image/reachability-metadata.json"
	);
	private static final Path TEST_OUT = Path.of(
			"src/test/resources/META-INF/native-image/reachability-metadata.json"
	);

	/**
	 * Reflection {@code type} prefixes that belong in the production image.
	 * Everything else the agent traced is supplied by the GraalVM
	 * reachability-metadata repository and is routed to the test scope.
	 */
	private static final List<String> MAIN_TYPE_PREFIXES = List.of(
			"io.github.arlol.", // project records (Jackson + Pkl)
			"com.sun.jna.", // lazysodium's JNI binding
			"com.goterl.lazysodium." // secret encryption
	);

	/**
	 * Resource glob prefixes that belong in the production image. As with
	 * reflection, everything else (JDK service providers, ICU data, Truffle /
	 * GraalVM runtime files, ...) is supplied by the GraalVM metadata
	 * repository. The survivors are Pkl's own resources and the native
	 * libraries, none of which the repository covers.
	 */
	private static final List<String> MAIN_RESOURCE_PREFIXES = List.of(
			"META-INF/org/pkl/", // Pkl java-config class mappers
			"META-INF/services/org.pkl.", // Pkl ServiceLoader providers
			"org/pkl/", // Pkl stdlib + Release.properties
			"com/sun/jna/" // JNA dispatch native library
	);

	/** Production resource glob substrings (platform dir varies by build). */
	private static final List<String> MAIN_RESOURCE_SUBSTRINGS = List.of(
			"libsodium" // lazysodium native library
	);

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final ObjectWriter WRITER = writer();

	public static void main(String[] args) throws Exception {
		Path input = args.length > 0 ? Path.of(args[0]) : INPUT;
		if (!Files.isRegularFile(input)) {
			System.err.println("ERROR: agent output not found: " + input);
			System.err.println("Run `./mvnw test -Dagent=true` first.");
			System.exit(1);
		}

		var doc = (ObjectNode) MAPPER.readTree(input.toFile());
		var reflection = (ArrayNode) doc.get("reflection");
		var resources = arrayOrEmpty(doc.get("resources"));

		var mainRefl = MAPPER.createArrayNode();
		var testRefl = MAPPER.createArrayNode();
		for (JsonNode e : reflection) {
			(isMainReflection(e) ? mainRefl : testRefl).add(e);
		}

		var mainRes = MAPPER.createArrayNode();
		var testRes = MAPPER.createArrayNode();
		for (JsonNode e : resources) {
			(isMainResource(e) ? mainRes : testRes).add(e);
		}

		augmentProjectRecords(mainRefl);

		write(MAIN_OUT, mainRefl, mainRes);
		write(TEST_OUT, testRefl, testRes);

		System.out.printf(
				"input:  %4d reflection  %3d resources%n",
				reflection.size(),
				resources.size()
		);
		System.out.printf(
				"  main: %4d reflection  %3d resources  -> %s%n",
				mainRefl.size(),
				mainRes.size(),
				MAIN_OUT
		);
		System.out.printf(
				"  test: %4d reflection  %3d resources  -> %s%n",
				testRefl.size(),
				testRes.size(),
				TEST_OUT
		);
	}

	private static boolean isMainReflection(JsonNode entry) {
		String type = entry.path("type").asText("");
		return MAIN_TYPE_PREFIXES.stream().anyMatch(type::startsWith);
	}

	private static boolean isMainResource(JsonNode entry) {
		JsonNode glob = entry.get("glob");
		if (glob == null) {
			// resource bundles (e.g. servlet LocalStrings) are all test-only
			return false;
		}
		String g = glob.asText();
		return MAIN_RESOURCE_PREFIXES.stream().anyMatch(g::startsWith)
				|| MAIN_RESOURCE_SUBSTRINGS.stream().anyMatch(g::contains);
	}

	/**
	 * Ensure every public record in the client/pkl packages is registered, so
	 * the project's own Jackson/Pkl types are covered even if untested.
	 */
	private static void augmentProjectRecords(ArrayNode mainRefl) {
		var byType = new TreeMap<String, ObjectNode>();
		for (JsonNode e : mainRefl) {
			byType.put(e.path("type").asText(), (ObjectNode) e);
		}
		try (ScanResult scan = new ClassGraph().enableClassInfo()
				.acceptPackages(
						"io.github.arlol.githubcheck.client",
						"io.github.arlol.githubcheck.pkl"
				)
				.scan()) {
			for (var classInfo : scan.getAllClasses()) {
				if (!classInfo.isPublic()) {
					continue;
				}
				var entry = byType
						.computeIfAbsent(classInfo.getName(), type -> {
							var node = MAPPER.createObjectNode();
							node.put("type", type);
							mainRefl.add(node);
							return node;
						});
				if (classInfo.isRecord()) {
					entry.put("allPublicFields", true);
					entry.put("allPublicMethods", true);
					entry.put("allPublicConstructors", true);
				} else if (classInfo.isStandardClass()) {
					entry.put("allPublicConstructors", true);
				}
			}
		}
	}

	private static void write(
			Path path,
			ArrayNode reflection,
			ArrayNode resources
	) throws Exception {
		var doc = MAPPER.createObjectNode();
		doc.set(
				"reflection",
				sorted(reflection, ReachabilityMetadata::typeKey)
		);
		doc.set(
				"resources",
				sorted(resources, ReachabilityMetadata::resourceKey)
		);
		Files.createDirectories(path.getParent());
		Files.writeString(path, WRITER.writeValueAsString(doc) + "\n");
	}

	private static ArrayNode sorted(
			ArrayNode array,
			java.util.function.Function<JsonNode, String> key
	) {
		var list = new ArrayList<JsonNode>();
		array.forEach(list::add);
		list.sort(Comparator.comparing(key));
		var out = MAPPER.createArrayNode();
		list.forEach(out::add);
		return out;
	}

	private static String typeKey(JsonNode entry) {
		return entry.path("type").asText("");
	}

	private static String resourceKey(JsonNode entry) {
		if (entry.hasNonNull("glob")) {
			return entry.get("glob").asText();
		}
		if (entry.hasNonNull("bundle")) {
			return entry.get("bundle").asText();
		}
		return entry.toString();
	}

	private static ArrayNode arrayOrEmpty(JsonNode node) {
		return node instanceof ArrayNode array ? array
				: MAPPER.createArrayNode();
	}

	private static ObjectWriter writer() {
		var printer = new DefaultPrettyPrinter().withSeparators(
				Separators.createDefaultInstance()
						.withObjectFieldValueSpacing(Separators.Spacing.AFTER)
						.withArrayEmptySeparator("")
		);
		printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
		return MAPPER.writer(printer);
	}

	private ReachabilityMetadata() {
	}

}
