package io.github.arlol.githubcheck.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

class ReachabilityMetadataTest {

	private static final Path METADATA_FILE = Path.of(
			"src/main/resources/META-INF/native-image/reachability-metadata.json"
	);

	@Test
	@EnabledIfEnvironmentVariable(named = "DRIFTY_REACHABILITY", matches = ".*")
	void regenerateClientEntries() throws Exception {
		var printer = new DefaultPrettyPrinter().withSeparators(
				Separators.createDefaultInstance()
						.withObjectFieldValueSpacing(Separators.Spacing.AFTER)
						.withArrayEmptySeparator("")
		);
		printer.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
		ObjectMapper mapper = new ObjectMapper()
				.setDefaultPrettyPrinter(printer);

		var existing = (ObjectNode) mapper.readTree(METADATA_FILE.toFile());
		var reflection = (ArrayNode) existing.get("reflection");

		var existingByType = new TreeMap<String, ObjectNode>();
		for (JsonNode entry : reflection) {
			existingByType.put(entry.get("type").asText(), (ObjectNode) entry);
		}

		try (ScanResult scan = new ClassGraph().enableClassInfo()
				.acceptPackages("io.github.arlol.githubcheck.client")
				.scan()) {
			for (var classInfo : scan.getAllClasses()) {
				if (!classInfo.isPublic()) {
					continue;
				}
				var type = classInfo.getName();
				var entry = Optional.ofNullable(existingByType.get(type))
						.orElseGet(() -> {
							var newEntry = mapper.createObjectNode();
							newEntry.put("type", type);
							reflection.add(newEntry);
							existingByType.put(type, newEntry);
							return newEntry;
						});

				if (classInfo.isRecord()) {
					entry.put("allPublicFields", true);
					entry.put("allPublicMethods", true);
					entry.put("allPublicConstructors", true);
				}
			}
		}

		try (ScanResult scan = new ClassGraph().enableClassInfo()
				.acceptPackages("io.github.arlol.githubcheck.pkl")
				.scan()) {
			for (var classInfo : scan.getAllClasses()) {
				if (!classInfo.isPublic()) {
					continue;
				}
				var type = classInfo.getName();
				var entry = Optional.ofNullable(existingByType.get(type))
						.orElseGet(() -> {
							var newEntry = mapper.createObjectNode();
							newEntry.put("type", type);
							existingByType.put(type, newEntry);
							return newEntry;
						});

				if (classInfo.isStandardClass()) {
					entry.put("allPublicConstructors", true);
				}
			}
		}

		var newReflection = mapper.createArrayNode();
		existingByType.forEach((_, value) -> newReflection.add(value));

		existing.set("reflection", reflection);

		var json = mapper.writer(printer).writeValueAsString(existing);
		Files.writeString(METADATA_FILE, json + "\n");

	}

}
