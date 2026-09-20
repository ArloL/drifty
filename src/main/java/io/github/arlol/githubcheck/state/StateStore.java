package io.github.arlol.githubcheck.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.Arrays;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Loads and saves the {@link DriftyState} JSON file.
 */
public class StateStore {

	/**
	 * How long an unconfirmed cache entry is kept. An entry costs about 1.5 KB
	 * and evicting one costs a charged request, so this is set by what is worth
	 * remembering, not by file size.
	 */
	private static final int CACHE_DAYS = 180;

	private final ObjectMapper mapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			.setSerializationInclusion(JsonInclude.Include.NON_NULL)
			.enable(SerializationFeature.INDENT_OUTPUT);

	public DriftyState load(Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			return new DriftyState();
		}
		DriftyState state;
		try {
			state = mapper.readValue(path.toFile(), DriftyState.class);
		} catch (IOException e) {
			throw new IOException(
					"Cannot read drifty state file " + path
							+ " — delete it and the next run costs one"
							+ " uncached check, not a stack trace",
					e
			);
		}
		if (state.version != DriftyState.CURRENT_VERSION) {
			throw new IOException(
					"Unsupported drifty state file version " + state.version
							+ " in " + path + " (expected "
							+ DriftyState.CURRENT_VERSION + ")"
			);
		}
		return state;
	}

	/**
	 * Writes {@code state} to {@code path}, unless the file would say nothing
	 * drifty does not already know: a state holding neither a secret baseline
	 * nor a cached response creates no file at all, and a state that serializes
	 * to what the file already holds leaves it untouched.
	 * <p>
	 * The write goes through a sibling temp file and an atomic move rather than
	 * truncating {@code path} in place: this file holds secret baselines the
	 * class doc calls truth drifty cannot recover, and a check now writes it on
	 * every run, not only under {@code --fix}. A crash between truncate and
	 * last byte would otherwise leave a file Jackson cannot parse and no
	 * baseline recoverable. The temp file sits beside {@code path} because
	 * {@code ATOMIC_MOVE} across filesystems throws.
	 */
	public void save(Path path, DriftyState state) throws IOException {
		state.pruneCache(LocalDate.now().minusDays(CACHE_DAYS));
		if (state.isEmpty()) {
			return;
		}
		byte[] json = mapper.writeValueAsBytes(state);
		if (Files.isRegularFile(path)
				&& Arrays.equals(Files.readAllBytes(path), json)) {
			return;
		}
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			createOwnerOnly(tmp);
			Files.write(tmp, json);
			// ATOMIC_MOVE carries the mode with the inode, so the file that
			// lands at path is the one created above.
			Files.move(
					tmp,
					path,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING
			);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Creates {@code tmp} readable and writable by its owner and nobody else.
	 * <p>
	 * The mode is an attribute of the <em>create</em>, not a chmod afterwards:
	 * setting it after the write leaves a window in which the salted secret
	 * hashes and the cached response bodies — organization settings, member
	 * logins, webhook payload URLs — sit in a world-readable file, which on a
	 * shared CI runner is the whole of the exposure.
	 * <p>
	 * Windows has no POSIX modes and throws
	 * {@link UnsupportedOperationException} for the attribute; there the file
	 * inherits the directory's ACL, which is what governs it on that platform.
	 * drifty ships a Windows binary, so this falls back rather than failing the
	 * run.
	 */
	private static void createOwnerOnly(Path tmp) throws IOException {
		Files.deleteIfExists(tmp);
		try {
			Files.createFile(
					tmp,
					PosixFilePermissions.asFileAttribute(
							PosixFilePermissions.fromString("rw-------")
					)
			);
		} catch (UnsupportedOperationException e) {
			Files.createFile(tmp);
		}
	}

}
