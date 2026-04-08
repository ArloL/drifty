package io.github.arlol.githubcheck.drift;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public abstract class DriftGroup {

	public abstract String name();

	public abstract List<DriftFix> detect();

	protected static List<DriftItem> compare(
			String path,
			Object wanted,
			Object got
	) {
		return ocompare(path, wanted, got).map(List::of).orElse(List.of());
	}

	protected static Optional<DriftItem> ocompare(
			String path,
			Object wanted,
			Object got
	) {
		if (!Objects.equals(wanted, got)) {
			return Optional.of(new DriftItem.FieldMismatch(path, wanted, got));
		}
		return Optional.empty();
	}

	protected static <T> List<DriftItem> compare(
			String path,
			Collection<T> wanted,
			Collection<T> got
	) {
		return ocompare(path, wanted, got).map(List::of).orElse(List.of());
	}

	protected static <T> Optional<DriftItem> ocompare(
			String path,
			Collection<T> wanted,
			Collection<T> got
	) {
		Set<T> missing = new HashSet<>(wanted);
		missing.removeAll(got);
		Set<T> extra = new HashSet<>(got);
		extra.removeAll(wanted);
		if (!missing.isEmpty() || !extra.isEmpty()) {
			return Optional.of(new DriftItem.SetDrift(path, missing, extra));
		}
		return Optional.empty();
	}

	@SafeVarargs
	protected static <T> List<T> combine(Collection<T>... lists) {
		return Stream.of(lists).flatMap(Collection::stream).toList();
	}

}
