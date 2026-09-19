package io.github.arlol.githubcheck.client;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;

import io.github.arlol.githubcheck.client.ApiContract.SchemaNode;

/**
 * Walks a wire record against the endpoint schema it claims, and says where the
 * two disagree.
 * <p>
 * Six kinds of disagreement, each of which has produced a wrong answer rather
 * than an error somewhere in this codebase's history or could:
 * <ol>
 * <li>a declared name the spec does not carry — the silent-null class, because
 * the client mapper sets {@code FAIL_ON_UNKNOWN_PROPERTIES} to false;
 * <li>a spec property nothing declares — a feature gap;
 * <li>a Java enum constant the spec does not list — a value GitHub answers 422
 * to, reported as fixed;
 * <li>a spec enum value the Java enum lacks — a value GitHub sends that Jackson
 * cannot parse;
 * <li>a Java type the spec contradicts — the {@code {"status": "enabled"}}
 * wrapper class of bug;
 * <li>a primitive component against a property the spec marks nullable — the
 * mapper sets {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so that is a crash in the
 * wild.
 * </ol>
 * <b>Values are enforced in both directions; fields are staged.</b> A missing
 * enum constant or rule subtype is not a gap to schedule — it either throws or
 * makes drifty silently blind to something it was pointed at — so those run
 * whatever {@link Options#reverse()} says. Reverse on object properties is what
 * the rollout brings in endpoint by endpoint.
 */
public final class ContractComparison {

	/** One disagreement, named well enough to act on without a debugger. */
	public record Finding(
			String endpoint,
			String direction,
			String path,
			String detail
	) {

		@Override
		public String toString() {
			return endpoint + "  (" + direction + ")  "
					+ (path.isEmpty() ? "<root>" : path) + "\n      " + detail;
		}

	}

	/**
	 * @param reverse      whether a spec property nothing declares is a finding
	 * @param unmanaged    paths the spec carries that drifty deliberately does
	 *                     not model
	 * @param undocumented paths drifty reads that the spec does not carry
	 * @param rootMetadata names that need no declaration when they sit at the
	 *                     root of a response object. Root-level only: a
	 *                     webhook's {@code config.url} is the payload URL, a
	 *                     managed setting at depth 1, and a blanket rule would
	 *                     hide it.
	 */
	public record Options(
			boolean reverse,
			Set<String> unmanaged,
			Set<String> undocumented,
			Predicate<String> rootMetadata
	) {

		public static Options forward() {
			return new Options(false, Set.of(), Set.of(), name -> false);
		}

	}

	private static final Set<Class<?>> FREE_FORM = Set
			.of(JsonNode.class, Object.class);

	private final ObjectMapper mapper = WireShape.clientMapper();
	private final String endpoint;
	private final String direction;
	private final boolean forReading;
	private final Options options;
	private final List<Finding> findings = new ArrayList<>();
	private final Set<String> consumed = new LinkedHashSet<>();

	public ContractComparison(
			String endpoint,
			String direction,
			Options options
	) {
		this.endpoint = endpoint;
		this.direction = direction;
		this.forReading = "response".equals(direction);
		this.options = options;
	}

	/** Every disagreement between {@code root} and {@code schema}. */
	public List<Finding> compare(SchemaNode schema, Class<?> root) {
		// A listing endpoint answers with an array and the record bound to it
		// is one element: GitHubClient parses the items, never the array. So
		// descend before comparing, or every listing record would be reported
		// as an object where the spec says array.
		SchemaNode start = schema;
		if (start != null && "array".equals(start.type())
				&& start.items() != null
				&& !Collection.class.isAssignableFrom(root)
				&& !root.isArray()) {
			start = start.items();
		}
		walk(
				start,
				TypeFactory.defaultInstance().constructType(root),
				"",
				0,
				new HashSet<>()
		);
		return List.copyOf(findings);
	}

	/**
	 * Exclusions this comparison had no use for.
	 * <p>
	 * A stale exclusion is how a check quietly stops covering what it claims
	 * to, so these are a failure — but not this comparison's to report. One
	 * record serves up to four endpoints off one pair of lists, and an entry
	 * that answers a finding on the organization ruleset endpoints has nothing
	 * to answer on the repository ones. Staleness is only true when every
	 * binding of the record had no use for it, which the caller is what knows.
	 */
	public Set<String> unusedExclusions() {
		Set<String> unused = new LinkedHashSet<>();
		for (String entry : options.unmanaged()) {
			if (!consumed.contains(pathOf(entry))) {
				unused.add(pathOf(entry));
			}
		}
		for (String entry : options.undocumented()) {
			if (!consumed.contains(pathOf(entry))) {
				unused.add(pathOf(entry));
			}
		}
		return unused;
	}

	/** The paths an exclusion answered for. */
	public Set<String> consumedExclusions() {
		return Set.copyOf(consumed);
	}

	// ─── The walk
	// ────────────────────────────────────────────────────────────

	private void walk(
			SchemaNode schema,
			JavaType type,
			String path,
			int depth,
			Set<Class<?>> open
	) {
		walk(schema, type, path, depth, open, null);
	}

	private void walk(
			SchemaNode schema,
			JavaType type,
			String path,
			int depth,
			Set<Class<?>> open,
			String implied
	) {
		if (schema == null) {
			return;
		}
		Class<?> raw = type.getRawClass();

		if (FREE_FORM.contains(raw) || JsonNode.class.isAssignableFrom(raw)) {
			return;
		}

		if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
			checkType(schema, raw, path);
			JavaType element = type.getContentType();
			if (element != null && schema.items() != null) {
				// The element keeps the property's path: a listing response's
				// items are its root object, so their properties are at depth
				// 0 and the root metadata rule applies to them.
				walk(schema.items(), element, path, depth, open);
			}
			return;
		}

		if (raw.isEnum()) {
			checkType(schema, raw, path);
			checkEnumValues(schema, raw, path);
			return;
		}

		Map<String, Class<?>> subtypes = WireShape.subtypes(raw);
		if (!subtypes.isEmpty()) {
			checkSubtypes(schema, subtypes, path, depth, open);
			return;
		}

		if (Map.class.isAssignableFrom(raw)) {
			// A free-form object: the spec names properties, the record holds
			// whatever GitHub sent. Nothing to compare but the type.
			checkType(schema, raw, path);
			return;
		}

		if (raw.isRecord()) {
			checkType(schema, raw, path);
			if (open.add(raw)) {
				try {
					checkProperties(schema, type, path, depth, open, implied);
				} finally {
					open.remove(raw);
				}
			}
			return;
		}

		checkType(schema, raw, path);
	}

	/**
	 * @param implied a property the wire carries that no component declares
	 *                because Jackson writes it: a polymorphic type's
	 *                discriminator comes from {@code @JsonTypeInfo}, so
	 *                {@code Rule.Creation} never declares {@code type} and is
	 *                not missing it.
	 */
	private void checkProperties(
			SchemaNode schema,
			JavaType type,
			String path,
			int depth,
			Set<Class<?>> open,
			String implied
	) {
		Map<String, WireShape.Property> declared = new LinkedHashMap<>();
		for (WireShape.Property property : WireShape
				.properties(mapper, type, forReading)) {
			declared.put(property.wireName(), property);
		}

		for (WireShape.Property property : declared.values()) {
			String full = join(path, property.wireName());
			SchemaNode child = schema.properties().get(property.wireName());
			if (child == null) {
				if (!consume(options.undocumented(), full)) {
					add(
							full,
							"declared by the record; the spec does not carry it here"
					);
				}
				continue;
			}
			if (forReading && child.nullable()
					&& property.type().getRawClass().isPrimitive()) {
				add(
						full,
						"the spec marks it nullable and the component is a"
								+ " primitive; the client mapper sets"
								+ " FAIL_ON_NULL_FOR_PRIMITIVES, so a null here"
								+ " ends the run"
				);
			}
			walk(child, property.type(), full, depth + 1, open);
		}

		if (!options.reverse()) {
			return;
		}
		for (String specName : schema.properties().keySet()) {
			if (declared.containsKey(specName) || specName.equals(implied)) {
				continue;
			}
			String full = join(path, specName);
			if (consume(options.unmanaged(), full)) {
				continue;
			}
			if (depth == 0 && options.rootMetadata().test(specName)) {
				continue;
			}
			add(full, "carried by the spec; no component declares it");
		}
	}

	private void checkEnumValues(SchemaNode schema, Class<?> raw, String path) {
		if (schema.enumValues().isEmpty()) {
			return;
		}
		Set<String> java = WireShape.enumValues(mapper, raw);
		for (String value : java) {
			if (!schema.enumValues().contains(value)
					&& !consume(options.undocumented(), path + "#" + value)) {
				add(
						path + "#" + value,
						raw.getSimpleName() + " sends \"" + value
								+ "\"; the spec allows " + schema.enumValues()
				);
			}
		}
		for (String value : schema.enumValues()) {
			if (!java.contains(value)
					&& !consume(options.unmanaged(), path + "#" + value)) {
				add(
						path + "#" + value,
						"the spec allows \"" + value + "\" and "
								+ raw.getSimpleName()
								+ " has no constant for it"
				);
			}
		}
	}

	private void checkSubtypes(
			SchemaNode schema,
			Map<String, Class<?>> subtypes,
			String path,
			int depth,
			Set<Class<?>> open
	) {
		if (schema.oneOf().isEmpty()) {
			return;
		}
		for (Map.Entry<String, Class<?>> subtype : subtypes.entrySet()) {
			SchemaNode branch = schema.oneOf().get(subtype.getKey());
			String full = path + "[" + subtype.getKey() + "]";
			if (branch == null) {
				if (!consume(options.undocumented(), full)) {
					add(
							full,
							"@JsonSubTypes names it; the spec has no such branch"
					);
				}
				continue;
			}
			walk(
					branch,
					TypeFactory.defaultInstance()
							.constructType(subtype.getValue()),
					full,
					depth + 1,
					open,
					schema.discriminator()
			);
		}
		for (String branch : schema.oneOf().keySet()) {
			if (subtypes.containsKey(branch)) {
				continue;
			}
			String full = path + "[" + branch + "]";
			if (!consume(options.unmanaged(), full)) {
				add(
						full,
						"the spec has this branch and @JsonSubTypes has no"
								+ " subtype for it, so it deserializes as the"
								+ " catch-all and nothing compares it"
				);
			}
		}
	}

	private void checkType(SchemaNode schema, Class<?> raw, String path) {
		if (schema.type() == null) {
			return;
		}
		Set<String> accepted = wireTypes(raw);
		if (accepted.isEmpty() || accepted.contains(schema.type())) {
			return;
		}
		add(
				path,
				raw.getSimpleName() + " reads as " + accepted
						+ "; the spec says \"" + schema.type() + "\""
		);
	}

	/**
	 * The JSON types a Java type can legitimately carry. Empty means "nothing
	 * worth asserting" — a type the walk has no opinion about is not a finding.
	 */
	private static Set<String> wireTypes(Class<?> raw) {
		if (raw == String.class || raw.isEnum()
				|| CharSequence.class.isAssignableFrom(raw)
				|| Temporal.class.isAssignableFrom(raw)) {
			return Set.of("string");
		}
		if (raw == Boolean.class || raw == boolean.class) {
			return Set.of("boolean");
		}
		if (raw == Integer.class || raw == int.class || raw == Long.class
				|| raw == long.class || raw == Short.class || raw == short.class
				|| raw == BigInteger.class) {
			return Set.of("integer", "number");
		}
		if (raw == Double.class || raw == double.class || raw == Float.class
				|| raw == float.class || raw == BigDecimal.class) {
			return Set.of("number", "integer");
		}
		if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
			return Set.of("array");
		}
		if (Map.class.isAssignableFrom(raw) || raw.isRecord()) {
			return Set.of("object");
		}
		return Set.of();
	}

	// ─── Exclusions
	// ──────────────────────────────────────────────────────────

	/** The path half of a {@code "path — reason"} entry. */
	static String pathOf(String entry) {
		int dash = entry.indexOf(" — ");
		return (dash < 0 ? entry : entry.substring(0, dash)).trim();
	}

	private boolean consume(Set<String> entries, String path) {
		for (String entry : entries) {
			if (pathOf(entry).equals(path)) {
				consumed.add(path);
				return true;
			}
		}
		return false;
	}

	private static String join(String path, String name) {
		return path.isEmpty() ? name : path + "." + name;
	}

	private void add(String path, String detail) {
		findings.add(new Finding(endpoint, direction, path, detail));
	}

}
