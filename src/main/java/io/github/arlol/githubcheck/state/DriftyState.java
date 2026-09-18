package io.github.arlol.githubcheck.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;

import io.github.arlol.githubcheck.client.ResponseCache;

/**
 * Persistent record of what drifty last observed and pushed for each managed
 * secret. GitHub never returns a secret's value, so drifty remembers two
 * fingerprints per secret: the {@code updated_at} timestamp (detects
 * out-of-band changes) and a salted hash of the value it last pushed (detects
 * rotation of the desired value).
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class DriftyState implements ResponseCache {

	public record SecretRecord(
			String updatedAt,
			String valueHash
	) {
	}

	/**
	 * One cached response. {@code lastValidated} is an ISO date rather than a
	 * timestamp because the only question asked of it is how many days old it
	 * is.
	 */
	public record CacheEntry(
			String etag,
			String body,
			String link,
			String lastValidated
	) {
	}

	@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
	public static class RepoState {

		ConcurrentHashMap<String, SecretRecord> actionSecrets = new ConcurrentHashMap<>();
		ConcurrentHashMap<String, ConcurrentHashMap<String, SecretRecord>> environmentSecrets = new ConcurrentHashMap<>();
		/**
		 * Webhook secrets, keyed by the config's name for the hook. Added
		 * without a version bump, like {@code organizations}.
		 */
		ConcurrentHashMap<String, SecretRecord> webhookSecrets = new ConcurrentHashMap<>();

	}

	@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
	public static class OrgState {

		ConcurrentHashMap<String, SecretRecord> actionSecrets = new ConcurrentHashMap<>();
		ConcurrentHashMap<String, SecretRecord> webhookSecrets = new ConcurrentHashMap<>();

	}

	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	static final int CURRENT_VERSION = 1;

	int version = CURRENT_VERSION;
	String salt;
	ConcurrentHashMap<String, RepoState> repositories = new ConcurrentHashMap<>();
	/**
	 * Organization secrets, added without a version bump: a file written before
	 * this simply has no key here, and the reader tolerates unknown properties
	 * in the other direction.
	 */
	ConcurrentHashMap<String, OrgState> organizations = new ConcurrentHashMap<>();
	/**
	 * Bodies behind ETags, keyed by the URL without its host. Added without a
	 * version bump, like {@code organizations}: an older drifty ignores the key
	 * and a newer one treats a file without it as a cold cache.
	 */
	ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

	/**
	 * Whether the state holds no secret record. A salt generated during this
	 * run does not count: nothing recorded depends on it yet, so the next run
	 * is free to generate a different one.
	 */
	@JsonIgnore
	public boolean isEmpty() {
		return cache.isEmpty()
				&& repositories.values().stream().allMatch(DriftyState::isEmpty)
				&& organizations.values()
						.stream()
						.allMatch(
								orgState -> orgState.actionSecrets.isEmpty()
										&& orgState.webhookSecrets.isEmpty()
						);
	}

	private static boolean isEmpty(RepoState repoState) {
		return repoState.actionSecrets.isEmpty()
				&& repoState.webhookSecrets.isEmpty()
				&& repoState.environmentSecrets.values()
						.stream()
						.allMatch(Map::isEmpty);
	}

	public SecretRecord webhookSecretRecord(String repo, String name) {
		RepoState repoState = repositories.get(repo);
		return repoState == null ? null : repoState.webhookSecrets.get(name);
	}

	public SecretRecord orgWebhookSecretRecord(String org, String name) {
		OrgState orgState = organizations.get(org);
		return orgState == null ? null : orgState.webhookSecrets.get(name);
	}

	public void recordWebhookSecret(
			String repo,
			String name,
			String updatedAt,
			String valueHash
	) {
		repoState(repo).webhookSecrets
				.put(name, new SecretRecord(updatedAt, valueHash));
	}

	public void recordOrgWebhookSecret(
			String org,
			String name,
			String updatedAt,
			String valueHash
	) {
		organizations.computeIfAbsent(org, key -> new OrgState()).webhookSecrets
				.put(name, new SecretRecord(updatedAt, valueHash));
	}

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

	public SecretRecord orgActionSecretRecord(String org, String name) {
		OrgState orgState = organizations.get(org);
		return orgState == null ? null : orgState.actionSecrets.get(name);
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

	public void recordOrgActionSecret(
			String org,
			String name,
			String updatedAt,
			String valueHash
	) {
		organizations.computeIfAbsent(org, key -> new OrgState()).actionSecrets
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

	@Override
	public ResponseCache.Entry lookup(String key) {
		CacheEntry entry = cache.get(key);
		return entry == null ? null
				: new ResponseCache.Entry(
						entry.etag(),
						entry.body(),
						entry.link()
				);
	}

	@Override
	public void store(String key, String etag, String body, String link) {
		cache.put(key, new CacheEntry(etag, body, link, today()));
	}

	@Override
	public void confirm(String key) {
		cache.computeIfPresent(
				key,
				(unused, entry) -> new CacheEntry(
						entry.etag(),
						entry.body(),
						entry.link(),
						today()
				)
		);
	}

	private static String today() {
		return LocalDate.now().toString();
	}

}
