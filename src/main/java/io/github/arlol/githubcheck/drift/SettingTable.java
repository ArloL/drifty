package io.github.arlol.githubcheck.drift;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.arlol.githubcheck.client.GitHubApiException;

/**
 * A group's managed settings, compared field by field and written in one PATCH.
 * <p>
 * Both settings groups had their own copy of this — 73 identical lines apart
 * from the builder type and the client call, and the two copies of the comment
 * below had already drifted apart in wording. What is duplicated when it is
 * duplicated is not the table but the rule the table is written for, so the
 * rule lives here and the rows stay with the group that knows them.
 *
 * @param <B> the request builder the writes accumulate into
 * @param <A> the entity's actual state, which each row reads its setting from —
 *            before the write to compare, and after it to confirm
 */
public final class SettingTable<B, A> {

	/**
	 * One managed setting: what the config wants, where GitHub's value is read
	 * from, and how to put it into a PATCH body.
	 * <p>
	 * Pairing the comparison with the write is what keeps the request to the
	 * settings that actually drifted. Building the body from the desired config
	 * instead sends every field on every fix, and two fields answer 422 on an
	 * account that cannot hold them even when they already hold the wanted
	 * value — {@code allow_forking} against an org with
	 * {@code members_can_fork_private_repositories} off, and
	 * {@code members_can_create_internal_repositories} outside Enterprise — so
	 * a description change would fail over a setting that had not drifted.
	 * <p>
	 * {@code read} is a function rather than the value it returns because the
	 * same accessor answers twice: on the state the check read, and on the
	 * state GitHub answers the PATCH with.
	 *
	 * @param write       {@code null} for a setting drifty reports but does not
	 *                    change, paired with {@link #unfixableReason}
	 * @param unavailable whether GitHub does not offer the setting to this
	 *                    entity at all, which the report says in the item
	 *                    itself rather than only when a fix is attempted
	 */
	public record Setting<B, A>(
			String path,
			@Nullable Object wanted,
			Function<A, @Nullable Object> read,
			@Nullable Consumer<B> write,
			@Nullable String unfixableReason,
			boolean unavailable
	) {

		public static <B, A> Setting<B, A> of(
				String path,
				Object wanted,
				Function<A, @Nullable Object> read,
				Consumer<B> write
		) {
			return new Setting<>(path, wanted, read, write, null, false);
		}

		public static <B, A> Setting<B, A> checkOnly(
				String path,
				Object wanted,
				Function<A, @Nullable Object> read,
				String reason
		) {
			return new Setting<>(path, wanted, read, null, reason, false);
		}

		/**
		 * A setting GitHub does not offer here: compared as usual, so a config
		 * that wants what GitHub already has matches, and otherwise reported
		 * with {@code reason} and never written.
		 */
		public static <B, A> Setting<B, A> unavailable(
				String path,
				Object wanted,
				Function<A, @Nullable Object> read,
				String reason
		) {
			return new Setting<>(path, wanted, read, null, reason, true);
		}

		boolean drifted(A actual) {
			return !Objects.equals(wanted, read.apply(actual));
		}

		boolean writable() {
			return write != null;
		}

		DriftItem item(A actual) {
			if (unavailable && unfixableReason != null) {
				return new DriftItem.Unavailable(path, wanted, unfixableReason);
			}
			return new DriftItem.FieldMismatch(
					path,
					wanted,
					read.apply(actual)
			);
		}

	}

	private final Supplier<B> newBuilder;
	private final Function<B, A> send;
	private final A actual;
	private final List<Setting<B, A>> settings;

	/**
	 * @param newBuilder a fresh, empty request builder
	 * @param send       finishes one builder, sends it and answers the state
	 *                   GitHub responds with — the group supplies this because
	 *                   only it knows which endpoint and which path parameters
	 *                   the request needs
	 * @param actual     the state the check read
	 */
	public SettingTable(
			Supplier<B> newBuilder,
			Function<B, A> send,
			A actual,
			List<Setting<B, A>> settings
	) {
		this.newBuilder = newBuilder;
		this.send = send;
		this.actual = actual;
		this.settings = List.copyOf(settings);
	}

	/** The drifted rows as one fix, which is what a group's detect returns. */
	public List<DriftFix> detect() {
		List<Setting<B, A>> drifted = settings.stream()
				.filter(setting -> setting.drifted(actual))
				.toList();
		List<DriftItem> items = drifted.stream()
				.map(setting -> setting.item(actual))
				.toList();
		// A table whose only drift is rows nothing writes offers no run: the
		// Would fix: preview would name the group and --fix send nothing.
		return List.of(
				new DriftFix(
						items,
						() -> fix(drifted),
						drifted.stream().anyMatch(Setting::writable)
				)
		);
	}

	private FixResult fix(List<Setting<B, A>> drifted) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		var writable = new ArrayList<Setting<B, A>>();
		for (Setting<B, A> setting : drifted) {
			if (setting.writable()) {
				writable.add(setting);
			} else {
				unfixed.add(
						new FixResult.Unfixed(
								setting.item(actual),
								setting.unfixableReason()
						)
				);
			}
		}
		if (!writable.isEmpty()) {
			unfixed.addAll(write(writable));
		}
		return new FixResult(unfixed);
	}

	/**
	 * Writes the drifted settings in one PATCH, and on rejection works out
	 * which of them GitHub actually refused.
	 * <p>
	 * GitHub applies the fields it accepts and rejects the rest, so a 422 over
	 * one field says nothing about the others — reporting the whole request as
	 * failed told the operator the opposite of what had happened, and the
	 * changes that did land showed up as still drifted. Re-sending each field
	 * on its own settles it: a field GitHub takes is fixed (the ones the batch
	 * already applied simply repeat), and only the field that fails again is
	 * reported, with its own error as the reason. A single-field request needs
	 * no second pass, since it is already its own attribution.
	 */
	private List<FixResult.Unfixed> write(List<Setting<B, A>> writable) {
		try {
			return notApplied(writable, sendAll(writable));
		} catch (GitHubApiException e) {
			if (writable.size() == 1) {
				return List.of(
						new FixResult.Unfixed(
								writable.getFirst().item(actual),
								e.getMessage()
						)
				);
			}
			return writeIndividually(writable);
		}
	}

	private List<FixResult.Unfixed> writeIndividually(
			List<Setting<B, A>> writable
	) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		for (Setting<B, A> setting : writable) {
			try {
				unfixed.addAll(
						notApplied(List.of(setting), sendAll(List.of(setting)))
				);
			} catch (GitHubApiException e) {
				unfixed.add(
						new FixResult.Unfixed(
								setting.item(actual),
								e.getMessage()
						)
				);
			}
		}
		return unfixed;
	}

	/**
	 * The written settings GitHub's answer does not carry the wanted value for,
	 * read through the same accessor the comparison used.
	 * <p>
	 * A 200 is not an applied change: GitHub accepts {@code allow_auto_merge}
	 * on a private repository of a Free account and leaves it off, since
	 * auto-merge needs branch protection the plan does not have. Taking the
	 * status for the result printed FIXED, and the next run reported the same
	 * drift, forever (issue #203). The PATCH answers with the whole entity, so
	 * confirming costs no request.
	 */
	private List<FixResult.Unfixed> notApplied(
			List<Setting<B, A>> written,
			A after
	) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		for (Setting<B, A> setting : written) {
			if (setting.drifted(after)) {
				unfixed.add(
						new FixResult.Unfixed(
								setting.item(actual),
								"GitHub accepted the change but did not apply it: "
										+ "it still reports "
										+ setting.read().apply(after)
						)
				);
			}
		}
		return unfixed;
	}

	private A sendAll(List<Setting<B, A>> settingsToWrite) {
		B builder = newBuilder.get();
		// Only settings whose writable() — that is, write() != null — said yes
		// reach this, so the lookup cannot miss; say so rather than leave a
		// bare call.
		settingsToWrite.forEach(
				setting -> Objects.requireNonNull(setting.write())
						.accept(builder)
		);
		return send.apply(builder);
	}

}
