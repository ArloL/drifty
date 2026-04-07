package io.github.arlol.githubcheck.drift;

import java.util.List;

public record DriftFix(
		List<DriftItem> items,
		FixAction fix
) {

	@FunctionalInterface
	public interface FixAction {

		FixResult execute();

	}

	public DriftFix {
		items = List.copyOf(items);
	}

	public DriftFix(DriftItem item, FixAction fix) {
		this(List.of(item), fix);
	}

}
