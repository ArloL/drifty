package io.github.arlol.githubcheck.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GitHub's own OpenAPI spec, reduced to what a wire-shape check needs and read
 * from the committed file {@code download-schemas.py --contract} writes.
 * <p>
 * The dereferenced spec is 70 MB and would have to be downloaded; this is the
 * 705 KB of it that says which property names, types and enum values an
 * endpoint carries. Nothing here reaches the network.
 */
public final class ApiContract {

	public static final Path BUNDLED = Path
			.of("src/test/resources/github-api-contract.json");

	/**
	 * One node of a reduced schema. Every component is optional: an absent key
	 * in the file becomes null or empty here rather than throwing, because the
	 * spec leaves most of them out most of the time.
	 * <p>
	 * {@code oneOf} is populated only for a <em>discriminated</em> union, keyed
	 * by the value of {@code discriminator}. Merging those branches would
	 * compare every subtype against the union of all of them.
	 */
	public record SchemaNode(
			String type,
			Set<String> enumValues,
			boolean nullable,
			Map<String, SchemaNode> properties,
			Set<String> required,
			SchemaNode items,
			String discriminator,
			Map<String, SchemaNode> oneOf
	) {

		public boolean hasProperties() {
			return !properties.isEmpty();
		}

	}

	private final String apiVersion;
	private final String specEtag;
	private final Map<String, Map<String, SchemaNode>> endpoints;

	private ApiContract(
			String apiVersion,
			String specEtag,
			Map<String, Map<String, SchemaNode>> endpoints
	) {
		this.apiVersion = apiVersion;
		this.specEtag = specEtag;
		this.endpoints = endpoints;
	}

	public static ApiContract bundled() {
		return load(BUNDLED);
	}

	public static ApiContract load(Path file) {
		try {
			JsonNode root = new ObjectMapper().readTree(Files.readString(file));
			Map<String, Map<String, SchemaNode>> endpoints = new LinkedHashMap<>();
			JsonNode node = root.path("endpoints");
			node.fieldNames().forEachRemaining(endpoint -> {
				JsonNode directions = node.get(endpoint);
				Map<String, SchemaNode> byDirection = new LinkedHashMap<>();
				for (String direction : new String[] { "request",
						"response" }) {
					if (directions.hasNonNull(direction)) {
						byDirection.put(
								direction,
								schema(directions.get(direction))
						);
					}
				}
				endpoints.put(endpoint, byDirection);
			});
			return new ApiContract(
					root.path("apiVersion").asText(""),
					root.path("specEtag").asText(""),
					endpoints
			);
		} catch (IOException e) {
			throw new UncheckedIOException(
					"Could not read " + file
							+ ". Refresh it with: python3 download-schemas.py --contract",
					e
			);
		}
	}

	private static SchemaNode schema(JsonNode node) {
		Map<String, SchemaNode> properties = new LinkedHashMap<>();
		JsonNode props = node.path("properties");
		props.fieldNames()
				.forEachRemaining(
						name -> properties.put(name, schema(props.get(name)))
				);

		Map<String, SchemaNode> variants = new LinkedHashMap<>();
		JsonNode oneOf = node.path("oneOf");
		oneOf.fieldNames()
				.forEachRemaining(
						name -> variants.put(name, schema(oneOf.get(name)))
				);

		return new SchemaNode(
				node.path("type").isTextual() ? node.path("type").asText()
						: null,
				strings(node.path("enum")),
				node.path("nullable").asBoolean(false),
				properties,
				strings(node.path("required")),
				node.hasNonNull("items") ? schema(node.get("items")) : null,
				node.path("discriminator").isTextual()
						? node.path("discriminator").asText()
						: null,
				variants
		);
	}

	private static Set<String> strings(JsonNode array) {
		Set<String> values = new LinkedHashSet<>();
		array.forEach(value -> values.add(value.asText()));
		return values;
	}

	public String apiVersion() {
		return apiVersion;
	}

	public String specEtag() {
		return specEtag;
	}

	public Set<String> endpoints() {
		return new TreeSet<>(endpoints.keySet());
	}

	public boolean has(String endpoint) {
		return endpoints.containsKey(endpoint);
	}

	/** The request or response schema, or null when the endpoint has none. */
	public SchemaNode schema(String endpoint, String direction) {
		return endpoints.getOrDefault(endpoint, Map.of()).get(direction);
	}

}
