package io.github.arlol.githubcheck.drift;

import java.util.List;

/**
 * A drifted item and the write that would resolve it.
 *
 * @param items      what drifted; empty means the group looked and found
 *                   nothing
 * @param fix        the write {@code --fix} runs
 * @param actionable whether that write can change anything. False for an item
 *                   drifty only reports — an extra secret it will not delete, a
 *                   collaborator it will not remove — whose fix returns the
 *                   same reason whatever the state on GitHub. The
 *                   {@code Would fix:} preview skips a group with no actionable
 *                   fix, so check mode does not offer a run that would do
 *                   nothing.
 */
public record DriftFix(
		List<DriftItem> items,
		FixAction fix,
		boolean actionable
) {

	@FunctionalInterface
	public interface FixAction {

		FixResult execute();

	}

	public DriftFix {
		items = List.copyOf(items);
	}

	public DriftFix(List<DriftItem> items, FixAction fix) {
		this(items, fix, true);
	}

	public DriftFix(DriftItem item, FixAction fix) {
		this(List.of(item), fix, true);
	}

	/** An item drifty reports and never writes, carrying the reason. */
	public static DriftFix reported(DriftItem item, String reason) {
		return reported(List.of(item), reason);
	}

	/** The same, for a group that reports its items as one fix. */
	public static DriftFix reported(List<DriftItem> items, String reason) {
		List<DriftItem> copy = List.copyOf(items);
		return new DriftFix(
				copy,
				() -> new FixResult(
						copy.stream()
								.map(
										item -> new FixResult.Unfixed(
												item,
												reason
										)
								)
								.toList()
				),
				false
		);
	}

}
