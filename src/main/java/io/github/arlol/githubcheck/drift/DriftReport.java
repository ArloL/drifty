package io.github.arlol.githubcheck.drift;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.arlol.githubcheck.CheckResult;

/**
 * What one entity's drift groups make of it: the report entry a check produces,
 * or the one a {@code --fix} run produces.
 * <p>
 * Repositories and organizations reach this by different routes — different
 * endpoints, different state records, different group-name enums — and then
 * answer the same five questions identically: which groups the config leaves
 * alone, which groups drifted, whether to write, what to render, and which
 * groups {@code --fix} would act on. Each checker used to answer them in its
 * own copy of this code, and every addition to the entry has had to be made
 * twice: the {@code unmanaged} list, the {@code Would fix:} preview of issue
 * #156, and the per-setting FIXED/FAILED reports SPEC.md asks for.
 * <p>
 * Failure handling stays with the callers. A repository's read throws checked
 * exceptions and an organization's does not, so what each has to catch is
 * decided by its own fetch, not by this.
 */
public final class DriftReport {

	private DriftReport() {
	}

	/**
	 * The groups that drifted, with their fixes.
	 * <p>
	 * A group is in only when it reported a drifted item. Most groups return a
	 * {@link DriftFix} whether or not anything drifted — its item list is what
	 * says — so keying on "returned a fix" put twenty of the twenty-seven
	 * repository groups in the map on every run. {@code --fix} was unaffected,
	 * since {@link DriftFixer#applyFixes} skips an item-less fix, but the keys
	 * are also the {@code Would fix:} preview, and that named groups the
	 * operator's entity had no drift in.
	 */
	public static <N extends Enum<N>> Map<DriftGroup<N>, List<DriftFix>> groupDrifts(
			List<DriftGroup<N>> groups
	) {
		Map<DriftGroup<N>, List<DriftFix>> groupDrifts = new LinkedHashMap<>();
		for (DriftGroup<N> group : groups) {
			List<DriftFix> fixes = group.detect();
			if (fixes.stream().anyMatch(fix -> !fix.items().isEmpty())) {
				groupDrifts.put(group, fixes);
			}
		}
		return groupDrifts;
	}

	/**
	 * One entity's entry, from the groups already built for it.
	 *
	 * @param managed the entity's own {@code managed} declaration, which is
	 *                where the report's unmanaged list comes from — not from
	 *                whatever was read, because reading less is not a
	 *                declaration
	 */
	public static <N extends Enum<N>> CheckResult.Entry entry(
			String name,
			ManagedGroups<N> managed,
			List<DriftGroup<N>> groups,
			boolean fix
	) {
		return entry(name, managed, groupDrifts(groups), fix);
	}

	/** The same, for a caller that already has the group drifts in hand. */
	public static <N extends Enum<N>> CheckResult.Entry entry(
			String name,
			ManagedGroups<N> managed,
			Map<DriftGroup<N>, List<DriftFix>> groupDrifts,
			boolean fix
	) {
		if (fix) {
			DriftFixer.FixOutcome outcome = DriftFixer.applyFixes(groupDrifts);
			return CheckResult.Entry.fixed(
					name,
					DriftFixer.render(outcome.unfixedItems()),
					DriftFixer.fixReports(outcome)
			);
		}

		List<String> unmanaged = unmanaged(managed);
		List<String> diffs = groupDrifts.values()
				.stream()
				.flatMap(List::stream)
				.flatMap(driftFix -> driftFix.items().stream())
				.map(DriftItem::message)
				.toList();
		if (diffs.isEmpty()) {
			return CheckResult.Entry.ok(name, unmanaged);
		}
		// In check mode, preview which groups --fix would act on.
		return CheckResult.Entry.drift(
				name,
				diffs,
				DriftFixer.fixPreview(groupDrifts),
				unmanaged
		);
	}

	/** The groups the entity leaves alone, as the report names them. */
	public static <N extends Enum<N>> List<String> unmanaged(
			ManagedGroups<N> managed
	) {
		return managed.unmanaged().stream().map(Object::toString).toList();
	}

}
