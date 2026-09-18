package io.github.arlol.githubcheck.client;

/**
 * Where {@link GitHubClient} keeps the body behind an ETag.
 * <p>
 * A 304 carries no body, so answering one means having kept the last. The port
 * is declared here rather than taken as a state type because the client has no
 * business knowing where the answers are written down; {@code DriftyState}
 * implements it and the state file holds the entries.
 * <p>
 * Nothing here is a cache in the HTTP sense: drifty revalidates every read and
 * ignores {@code max-age}, so an entry is only ever used to fill in a 304 that
 * GitHub has just sent. A drift detector that answered from a cache without
 * asking could report settings that had since changed.
 */
public interface ResponseCache {

	/**
	 * @param link the {@code Link} header the cached response carried, or null.
	 *             GitHub's 304 does not repeat it, and {@code remainingPages}
	 *             reads the page count off it — without this a cached first
	 *             page ends the listing.
	 */
	record Entry(
			String etag,
			String body,
			String link
	) {
	}

	/** The entry for {@code key}, or null when there is none. */
	Entry lookup(String key);

	/** Records the body of a 200 that carried {@code etag}. */
	void store(String key, String etag, String body, String link);

	/**
	 * Notes that {@code key} was confirmed current by a 304. What keeps an
	 * entry alive: pruning drops whatever no run has confirmed lately.
	 */
	void confirm(String key);

	/** Caches nothing, for a client with no state file behind it. */
	ResponseCache NONE = new ResponseCache() {

		@Override
		public Entry lookup(String key) {
			return null;
		}

		@Override
		public void store(String key, String etag, String body, String link) {
			// Deliberately nothing.
		}

		@Override
		public void confirm(String key) {
			// Deliberately nothing.
		}

	};

}
