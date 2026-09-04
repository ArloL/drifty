package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.arlol.githubcheck.CheckResult;

/**
 * Runs the fixes a set of drift groups produced and accounts for the result per
 * drift item, for repositories and organizations alike.
 */
public final class DriftFixer {

	private DriftFixer() {
	}

	/**
	 * What a fix run achieved: the items it resolved, and the ones it did not
	 * together with why.
	 */
	public record FixOutcome(
			List<DriftItem> fixed,
			List<FixResult.Unfixed> unfixed
	) {

		public FixOutcome {
			fixed = List.copyOf(fixed);
			unfixed = List.copyOf(unfixed);
		}

		public List<DriftItem> unfixedItems() {
			return unfixed.stream().map(FixResult.Unfixed::item).toList();
		}

	}

	/**
	 * Runs every fix and accounts for the result per drift item.
	 * <p>
	 * Accounting is by item, not by rendered message. Messages are built for
	 * people and are not unique — before drift paths were namespaced, thirteen
	 * groups rendered the same {@code "enabled: want=true got=false"}, and
	 * subtracting them with {@code List.removeAll} deleted every equal line, so
	 * one successful fix erased twelve other settings' drift including failed
	 * ones. Working from the items themselves removes that whole class of bug
	 * rather than relying on the paths staying distinct.
	 */
	public static FixOutcome applyFixes(
			Map<? extends DriftGroup<?>, List<DriftFix>> groupDrifts
	) {
		var fixed = new ArrayList<DriftItem>();
		var unfixed = new ArrayList<FixResult.Unfixed>();

		for (DriftFix driftFix : prerequisitesFirst(groupDrifts)) {
			if (!driftFix.items().isEmpty()) {
				apply(driftFix, fixed, unfixed);
			}
		}
		return new FixOutcome(fixed, unfixed);
	}

	public static List<String> render(List<DriftItem> items) {
		return items.stream().map(DriftItem::message).toList();
	}

	/**
	 * One FIXED/FAILED line per drift item, in the order the fixes ran.
	 */
	public static List<CheckResult.FixReport> fixReports(FixOutcome outcome) {
		var reports = new ArrayList<CheckResult.FixReport>();
		outcome.fixed()
				.forEach(
						item -> reports.add(
								new CheckResult.FixReport(
										item.path(),
										true,
										null
								)
						)
				);
		outcome.unfixed()
				.forEach(
						unfixed -> reports.add(
								new CheckResult.FixReport(
										unfixed.item().path(),
										false,
										unfixed.reason()
								)
						)
				);
		return reports;
	}

	/**
	 * Every fix to run, with the groups that declare themselves prerequisites
	 * ahead of the rest — unarchiving, today, because GitHub rejects writes to
	 * an archived repository and every other fix would fail.
	 */
	private static List<DriftFix> prerequisitesFirst(
			Map<? extends DriftGroup<?>, List<DriftFix>> groupDrifts
	) {
		var ordered = new ArrayList<DriftFix>();
		groupDrifts.entrySet()
				.stream()
				.filter(e -> e.getKey().runsBeforeOtherFixes())
				.forEach(e -> ordered.addAll(e.getValue()));
		groupDrifts.entrySet()
				.stream()
				.filter(e -> !e.getKey().runsBeforeOtherFixes())
				.forEach(e -> ordered.addAll(e.getValue()));
		return ordered;
	}

	/** Runs one fix and records each of its items as fixed or not. */
	private static void apply(
			DriftFix driftFix,
			List<DriftItem> fixed,
			List<FixResult.Unfixed> unfixed
	) {
		Map<DriftItem, FixResult.Unfixed> unfixedByItem;
		try {
			unfixedByItem = byItem(driftFix.fix().execute());
		} catch (RuntimeException e) {
			// The fix blew up, so nothing it covered got fixed.
			unfixed.addAll(allUnfixed(driftFix, reason(e)));
			return;
		}
		for (DriftItem item : driftFix.items()) {
			FixResult.Unfixed u = unfixedByItem.get(item);
			if (u == null) {
				fixed.add(item);
			} else {
				unfixed.add(u);
			}
		}
	}

	private static Map<DriftItem, FixResult.Unfixed> byItem(FixResult result) {
		return result.unfixedItems()
				.stream()
				.collect(
						Collectors.toMap(
								FixResult.Unfixed::item,
								u -> u,
								(a, _) -> a
						)
				);
	}

	private static List<FixResult.Unfixed> allUnfixed(
			DriftFix driftFix,
			String reason
	) {
		return driftFix.items()
				.stream()
				.map(item -> new FixResult.Unfixed(item, reason))
				.toList();
	}

	private static String reason(RuntimeException e) {
		return e.getMessage() == null ? e.getClass().getSimpleName()
				: e.getMessage();
	}

}
