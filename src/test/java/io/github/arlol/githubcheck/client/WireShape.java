package io.github.arlol.githubcheck.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

/**
 * What a wire record actually sends and reads, according to Jackson.
 * <p>
 * Every name here is asked of the mapper rather than derived. Reimplementing
 * {@code SNAKE_CASE} would make the check verify a second guess while the real
 * mapper does something else — and the wire names are exactly what is under
 * test, so a shared mistake would be invisible. The same goes for enum values:
 * a constant is serialised and unquoted, so {@code @JsonProperty("active")} and
 * a bare {@code PR_TITLE} are both read off the same path GitHub sees.
 */
public final class WireShape {

	/** A property as Jackson will write or read it. */
	public record Property(
			String wireName,
			JavaType type
	) {
	}

	private WireShape() {
	}

	/**
	 * The configuration {@code GitHubClient} builds, duplicated here.
	 * <p>
	 * It has to be the same four lines: a divergence would leave the check
	 * comparing the spec against a mapper nothing in production uses. It is
	 * duplicated rather than extracted because the point is to notice when the
	 * two drift apart, and a shared constant would hide exactly that.
	 */
	public static ObjectMapper clientMapper() {
		return new ObjectMapper()
				.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.configure(
						DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
						false
				)
				.configure(
						DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
						true
				);
	}

	/**
	 * The properties of {@code type}, named as they go on the wire.
	 *
	 * @param forReading true to introspect as a response body, false as a
	 *                   request body. The two differ:
	 *                   {@code @JsonInclude(NON_NULL)} is a serialization
	 *                   concern and {@code RepositoryUpdateRequest}'s whole
	 *                   nullable-wrapper design lives there.
	 */
	public static List<Property> properties(
			ObjectMapper mapper,
			JavaType type,
			boolean forReading
	) {
		BeanDescription description = forReading
				? mapper.getDeserializationConfig().introspect(type)
				: mapper.getSerializationConfig().introspect(type);
		List<Property> properties = new ArrayList<>();
		for (BeanPropertyDefinition property : description.findProperties()) {
			// A derived accessor is not a wire property when reading:
			// CodeScanningDefaultSetupResponse.isEnabled() computes a boolean
			// from `state`, and Jackson has nothing to deserialize it into.
			// Going the other way it would be serialised, so it counts there.
			boolean usable = forReading ? property.couldDeserialize()
					: property.couldSerialize();
			if (usable) {
				properties.add(
						new Property(
								property.getName(),
								property.getPrimaryType()
						)
				);
			}
		}
		return properties;
	}

	/** Every value of {@code enumType} as Jackson serialises it. */
	public static Set<String> enumValues(
			ObjectMapper mapper,
			Class<?> enumType
	) {
		Set<String> values = new LinkedHashSet<>();
		for (Object constant : enumType.getEnumConstants()) {
			try {
				String json = mapper.writeValueAsString(constant);
				values.add(
						json.startsWith("\"")
								? json.substring(1, json.length() - 1)
								: json
				);
			} catch (Exception e) {
				throw new IllegalStateException(
						"Could not serialise " + enumType.getName() + "."
								+ constant,
						e
				);
			}
		}
		return values;
	}

	/**
	 * Discriminator value to subtype, read off {@code @JsonSubTypes}.
	 * <p>
	 * Empty for a type that is not polymorphic. {@code Rule}'s
	 * {@code defaultImpl} catch-all is deliberately not in the map: it is what
	 * keeps an unknown rule type from throwing, not a rule type drifty models.
	 */
	public static Map<String, Class<?>> subtypes(Class<?> type) {
		JsonSubTypes subTypes = type.getAnnotation(JsonSubTypes.class);
		if (subTypes == null) {
			return Map.of();
		}
		Map<String, Class<?>> byName = new LinkedHashMap<>();
		for (JsonSubTypes.Type subType : subTypes.value()) {
			byName.put(subType.name(), subType.value());
		}
		return byName;
	}

	/** The property a polymorphic type is discriminated by, or null. */
	public static String discriminator(Class<?> type) {
		JsonTypeInfo info = type.getAnnotation(JsonTypeInfo.class);
		return info == null || info.property().isEmpty() ? null
				: info.property();
	}

}
