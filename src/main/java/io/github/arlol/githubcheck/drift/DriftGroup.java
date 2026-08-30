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

	/**
	 * Detects drift for this group. Paths are relative to the group: return
	 * {@code "enabled"}, not {@code "vulnerability_alerts.enabled"}, and the
	 * empty string for a group that has a single unnamed setting. Namespacing
	 * happens once, in {@link #detect()}.
	 */
	protected abstract List<DriftFix> detectDrift();

	/**
	 * Detects drift and namespaces every item under {@link #name()}.
	 * <p>
	 * A path is the identity of a drifted setting: it is what the report shows
	 * and what fix accounting matches on. Namespacing here — rather than in
	 * each of the two dozen groups — is what guarantees that two groups can
	 * never claim the same path, which they previously did: thirteen groups
	 * each emitted the bare path {@code "enabled"}.
	 */
	public final List<DriftFix> detect() {
		return detectDrift().stream()
				.map(
						fix -> new DriftFix(
								namespaceAll(fix.items()),
								namespaced(fix.fix())
						)
				)
				.toList();
	}

	/**
	 * Namespaces the items a fix reports back as unfixed. A group builds those
	 * from the same relative paths it uses in {@link #detectDrift()}, so
	 * without this they would not match the items the fix was attached to, and
	 * an unfixed item would be mistaken for a fixed one.
	 */
	private DriftFix.FixAction namespaced(DriftFix.FixAction action) {
		return () -> new FixResult(
				action.execute()
						.unfixedItems()
						.stream()
						.map(
								unfixed -> new FixResult.Unfixed(
										namespaced(unfixed.item()),
										unfixed.reason()
								)
						)
						.toList()
		);
	}

	private List<DriftItem> namespaceAll(List<DriftItem> items) {
		return items.stream().map(this::namespaced).toList();
	}

	private DriftItem namespaced(DriftItem item) {
		return item.withPath(namespaced(item.path()));
	}

	private String namespaced(String path) {
		return path == null || path.isEmpty() ? name() : name() + "." + path;
	}

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
