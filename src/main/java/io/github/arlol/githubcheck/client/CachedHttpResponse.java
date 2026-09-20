package io.github.arlol.githubcheck.client;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

/**
 * GitHub's 304, wearing the 200 it stands for.
 * <p>
 * Every caller above {@code GitHubClient.get} reads {@code statusCode()},
 * {@code body()} and the odd header, so filling those three in from the cached
 * entry is the whole of what a hit has to look like — the forty typed methods
 * above it never learn that anything was cached. Everything else is delegated
 * to the real 304, whose {@code uri()}, {@code request()} and {@code version()}
 * are the ones a caller would want anyway.
 */
final class CachedHttpResponse implements HttpResponse<String> {

	private final HttpResponse<String> notModified;
	private final ResponseCache.Entry entry;
	private final HttpHeaders headers;

	CachedHttpResponse(
			HttpResponse<String> notModified,
			ResponseCache.Entry entry
	) {
		this.notModified = notModified;
		this.entry = entry;
		this.headers = withCachedLink(notModified.headers(), entry.link());
	}

	/**
	 * A 304 does not repeat {@code Link}, so it is put back. Keeping the rest
	 * of the 304's headers matters: {@code X-RateLimit-Remaining} on it is
	 * current, and the one cached with the body is not.
	 * <p>
	 * GitHub sends no {@code Link} on a 304 today, but if that ever changes the
	 * live one is the current answer and the cached one is not, so a
	 * {@code Link} already on {@code live} is left alone.
	 */
	private static HttpHeaders withCachedLink(
			HttpHeaders live,
			@Nullable String link
	) {
		if (link == null || live.firstValue("link").isPresent()) {
			return live;
		}
		Map<String, List<String>> merged = new LinkedHashMap<>(live.map());
		merged.put("link", List.of(link));
		return HttpHeaders.of(merged, (name, value) -> true);
	}

	@Override
	public int statusCode() {
		return 200;
	}

	@Override
	public String body() {
		return entry.body();
	}

	@Override
	public HttpHeaders headers() {
		return headers;
	}

	@Override
	public HttpRequest request() {
		return notModified.request();
	}

	@Override
	public Optional<HttpResponse<String>> previousResponse() {
		return notModified.previousResponse();
	}

	@Override
	public Optional<SSLSession> sslSession() {
		return notModified.sslSession();
	}

	@Override
	public URI uri() {
		return notModified.uri();
	}

	@Override
	public HttpClient.Version version() {
		return notModified.version();
	}

}
