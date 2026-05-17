package io.github.arlol.githubcheck.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Loads and saves the {@link DriftyState} JSON file.
 */
public class StateStore {

	private final ObjectMapper mapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.setSerializationInclusion(JsonInclude.Include.NON_NULL)
			.enable(SerializationFeature.INDENT_OUTPUT);

	public DriftyState load(Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			return new DriftyState();
		}
		DriftyState state = mapper.readValue(path.toFile(), DriftyState.class);
		if (state.version != DriftyState.CURRENT_VERSION) {
			throw new IOException(
					"Unsupported drifty state file version " + state.version
							+ " in " + path + " (expected "
							+ DriftyState.CURRENT_VERSION + ")"
			);
		}
		return state;
	}

	public void save(Path path, DriftyState state) throws IOException {
		mapper.writeValue(path.toFile(), state);
	}

}
