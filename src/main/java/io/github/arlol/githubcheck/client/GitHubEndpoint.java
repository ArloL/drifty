package io.github.arlol.githubcheck.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the GitHub endpoints a wire record is the request or response body of,
 * so {@code GitHubApiContractTest} can compare what the record declares against
 * what GitHub's own OpenAPI spec says the endpoint carries.
 * <p>
 * Nothing else knows which record belongs to which endpoint: {@code
 * GitHubClient} builds its URLs by concatenation, so before this annotation the
 * binding existed only in Javadoc. The mapper sets {@code
 * FAIL_ON_UNKNOWN_PROPERTIES} to false, which means a component whose name does
 * not match the wire deserializes to null rather than failing — no exception,
 * no failed request, a wrong answer. This is what pins it.
 * <p>
 * Retention is {@code CLASS}: the annotation is read by ClassGraph from the
 * bytecode at test time and never reflectively at runtime, so it needs no entry
 * in {@code reachability-metadata.json}.
 * <p>
 * {@code download-schemas.py --contract} scans these sources for the endpoint
 * literals below and extracts exactly those endpoints, so a record naming a new
 * one pulls its schema in on the next refresh.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface GitHubEndpoint {

	/**
	 * Endpoints this record is the JSON request body of, each {@code "<METHOD>
	 * <path>"} with the path spelled as OpenAPI spells it, braces included:
	 * {@code "PATCH /repos/{owner}/{repo}"}.
	 */
	String[] request() default {};

	/**
	 * Endpoints this record is the JSON response body of, spelled as
	 * {@link #request()} describes. A record serving several endpoints lists
	 * them all, and is then compared against every one.
	 */
	String[] response() default {};

	/**
	 * Properties the spec carries that drifty deliberately does not model — the
	 * reverse direction's escape hatch. Each entry is a path, then
	 * {@code " — "}, then the reason: {@code "billing_email — drifty does not
	 * manage billing"}. The path is dotted for a nested property
	 * ({@code config.url}) and carries {@code #} for a single enum value
	 * ({@code target#actions}), so one value can be excluded without excluding
	 * the field holding it.
	 * <p>
	 * Not interchangeable with {@link #undocumented()}: this one says GitHub
	 * has a field drifty does not want, that one says drifty reads a field
	 * GitHub's spec has not caught up with.
	 */
	String[] unmanaged() default {};

	/**
	 * Properties this record reads that the spec does not carry, because
	 * GitHub's spec lags its API — the forward direction's escape hatch, in the
	 * same {@code "path — reason"} shape as {@link #unmanaged()}.
	 * <p>
	 * Without it the first spec lag turns correct code red, and the test gets
	 * deleted rather than fixed.
	 */
	String[] undocumented() default {};

}
