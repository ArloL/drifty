package io.github.arlol.githubcheck.drift;

import java.util.List;

public record FixResult(
		List<DriftItem> unfixedItems
) {

	public static FixResult success() {
		return new FixResult(List.of());
	}

	public FixResult {
		unfixedItems = List.copyOf(unfixedItems);
	}

}
