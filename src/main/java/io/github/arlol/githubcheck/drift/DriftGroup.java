package io.github.arlol.githubcheck.drift;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class DriftGroup {

	public abstract String name();

	public abstract List<DriftItem> detect();

	public abstract void fix();

	protected static List<DriftItem> compare(
			String path,
			Object wanted,
			Object got
	) {
		if (wanted != null && !Objects.equals(wanted, got)) {
			return List.of(new DriftItem.FieldMismatch(path, wanted, got));
		}
		return List.of();
	}

	protected static <T> List<DriftItem> compareSets(
			String path,
			Set<T> wanted,
			Set<T> got
	) {
		Set<T> missing = new HashSet<>(wanted);
		missing.removeAll(got);
		Set<T> extra = new HashSet<>(got);
		extra.removeAll(wanted);
		if (!missing.isEmpty() || !extra.isEmpty()) {
			return List.of(new DriftItem.SetDrift(path, missing, extra));
		}
		return List.of();
	}

}
