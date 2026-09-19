package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
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
 */
public final class SettingTable<B> {

	/**
	 * One managed setting: what the config wants, what GitHub has, and how to
	 * put it into a PATCH body.
	 * <p>
	 * Pairing the comparison with the write is what keeps the request to the
	 * settings that actually drifted. Building the body from the desired config
	 * instead sends every field on every fix, and two fields answer 422 on an
	 * account that cannot hold them even when they already hold the wanted
	 * value — {@code allow_forking} against an org with
	 * {@code members_can_fork_private_repositories} off, and
	 * {@code members_can_create_internal_repositories} outside Enterprise — so
	 * a description change would fail over a setting that had not drifted.
	 *
	 * @param write {@code null} for a setting drifty reports but does not
	 *              change, paired with {@link #unfixableReason}
	 */
	public record Setting<B>(
			String path,
			Object wanted,
			Object got,
			Consumer<B> write,
			String unfixableReason
	) {

		public static <B> Setting<B> of(
				String path,
				Object wanted,
				Object got,
				Consumer<B> write
		) {
			return new Setting<>(path, wanted, got, write, null);
		}

		public static <B> Setting<B> checkOnly(
				String path,
				Object wanted,
				Object got,
				String reason
		) {
			return new Setting<>(path, wanted, got, null, reason);
		}

		boolean drifted() {
			return !Objects.equals(wanted, got);
		}

		boolean writable() {
			return write != null;
		}

		DriftItem item() {
			return new DriftItem.FieldMismatch(path, wanted, got);
		}

	}

	private final Supplier<B> newBuilder;
	private final Consumer<B> send;
	private final List<Setting<B>> settings;

	/**
	 * @param newBuilder a fresh, empty request builder
	 * @param send       finishes one builder and sends it — the group supplies
	 *                   this because only it knows which endpoint and which
	 *                   path parameters the request needs
	 */
	public SettingTable(
			Supplier<B> newBuilder,
			Consumer<B> send,
			List<Setting<B>> settings
	) {
		this.newBuilder = newBuilder;
		this.send = send;
		this.settings = List.copyOf(settings);
	}

	/** The drifted rows as one fix, which is what a group's detect returns. */
	public List<DriftFix> detect() {
		List<Setting<B>> drifted = settings.stream()
				.filter(Setting::drifted)
				.toList();
		List<DriftItem> items = drifted.stream().map(Setting::item).toList();
		return List.of(new DriftFix(items, () -> fix(drifted)));
	}

	private FixResult fix(List<Setting<B>> drifted) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		var writable = new ArrayList<Setting<B>>();
		for (Setting<B> setting : drifted) {
			if (setting.writable()) {
				writable.add(setting);
			} else {
				unfixed.add(
						new FixResult.Unfixed(
								setting.item(),
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
	private List<FixResult.Unfixed> write(List<Setting<B>> writable) {
		try {
			sendAll(writable);
			return List.of();
		} catch (GitHubApiException e) {
			if (writable.size() == 1) {
				return List.of(
						new FixResult.Unfixed(
								writable.getFirst().item(),
								e.getMessage()
						)
				);
			}
			return writeIndividually(writable);
		}
	}

	private List<FixResult.Unfixed> writeIndividually(
			List<Setting<B>> writable
	) {
		var unfixed = new ArrayList<FixResult.Unfixed>();
		for (Setting<B> setting : writable) {
			try {
				sendAll(List.of(setting));
			} catch (GitHubApiException e) {
				unfixed.add(
						new FixResult.Unfixed(setting.item(), e.getMessage())
				);
			}
		}
		return unfixed;
	}

	private void sendAll(List<Setting<B>> settingsToWrite) {
		B builder = newBuilder.get();
		settingsToWrite.forEach(setting -> setting.write().accept(builder));
		send.accept(builder);
	}

}
