package io.github.arlol.githubcheck.drift;

import java.util.List;

/**
 * What a {@link DriftFix.FixAction} achieved. Items it could not resolve come
 * back paired with the reason, so the report can say why a setting is still
 * drifted instead of silently leaving it in the diff.
 */
public record FixResult(
		List<Unfixed> unfixedItems
) {

	/**
	 * A drift item the fix did not resolve, and why.
	 *
	 * @param item   the item, carrying the same path it was detected under
	 * @param reason human-readable explanation, shown in the report
	 */
	public record Unfixed(
			DriftItem item,
			String reason
	) {
	}

	public static FixResult success() {
		return new FixResult(List.of());
	}

	public static FixResult unfixed(DriftItem item, String reason) {
		return new FixResult(List.of(new Unfixed(item, reason)));
	}

	public FixResult {
		unfixedItems = List.copyOf(unfixedItems);
	}

}
