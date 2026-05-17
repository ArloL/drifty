package io.github.arlol.githubcheck.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

/**
 * Persistent record of what drifty last observed and pushed for each managed
 * secret. GitHub never returns a secret's value, so drifty remembers two
 * fingerprints per secret: the {@code updated_at} timestamp (detects
 * out-of-band changes) and a salted hash of the value it last pushed (detects
 * rotation of the desired value).
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class DriftyState {

	public record SecretRecord(
			String updatedAt,
			String valueHash
	) {
	}

	@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
	public static class RepoState {

		ConcurrentHashMap<String, SecretRecord> actionSecrets = new ConcurrentHashMap<>();
		ConcurrentHashMap<String, ConcurrentHashMap<String, SecretRecord>> environmentSecrets = new ConcurrentHashMap<>();

	}

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	static final int CURRENT_VERSION = 1;

	int version = CURRENT_VERSION;
	String salt;
	ConcurrentHashMap<String, RepoState> repositories = new ConcurrentHashMap<>();

	public SecretRecord actionSecretRecord(String repo, String name) {
		RepoState repoState = repositories.get(repo);
		return repoState == null ? null : repoState.actionSecrets.get(name);
	}

	public SecretRecord environmentSecretRecord(
			String repo,
			String env,
			String name
	) {
		RepoState repoState = repositories.get(repo);
		if (repoState == null) {
			return null;
		}
		Map<String, SecretRecord> secrets = repoState.environmentSecrets
				.get(env);
		return secrets == null ? null : secrets.get(name);
	}

	public void recordActionSecret(
			String repo,
			String name,
			String updatedAt,
			String valueHash
	) {
		repoState(repo).actionSecrets
				.put(name, new SecretRecord(updatedAt, valueHash));
	}

	public void recordEnvironmentSecret(
			String repo,
			String env,
			String name,
			String updatedAt,
			String valueHash
	) {
		repoState(repo).environmentSecrets
				.computeIfAbsent(env, key -> new ConcurrentHashMap<>())
				.put(name, new SecretRecord(updatedAt, valueHash));
	}

	/**
	 * Returns the salted SHA-256 hex of {@code value}, generating the random
	 * salt on first use. The salt defeats rainbow tables and hides equal values
	 * across secrets; it does not make a low-entropy secret uncrackable
	 * offline.
	 */
	public synchronized String hash(String value) {
		if (salt == null) {
			byte[] saltBytes = new byte[16];
			SECURE_RANDOM.nextBytes(saltBytes);
			salt = HexFormat.of().formatHex(saltBytes);
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(salt.getBytes(StandardCharsets.UTF_8));
			byte[] hashed = digest
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashed);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private RepoState repoState(String repo) {
		return repositories.computeIfAbsent(repo, key -> new RepoState());
	}

}
