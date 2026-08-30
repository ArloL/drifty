package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public sealed interface DriftItem {

	String path();

	String message();

	/**
	 * Returns a copy of this item under {@code newPath}. {@link DriftGroup}
	 * uses it to namespace every item under the group that produced it, so a
	 * path identifies one setting across the whole run.
	 */
	DriftItem withPath(String newPath);

	record FieldMismatch(
			String path,
			Object wanted,
			Object got
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": want=" + wanted + " got=" + got;
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new FieldMismatch(newPath, wanted, got);
		}

	}

	record SetDrift(
			String path,
			Set<?> missing,
			Set<?> extra
	) implements DriftItem {

		public SetDrift {
			missing = Set.copyOf(missing);
			extra = Set.copyOf(extra);
		}

		@Override
		public String message() {
			var parts = new ArrayList<String>();
			if (!missing.isEmpty()) {
				parts.add("missing: " + sorted(missing));
			}
			if (!extra.isEmpty()) {
				parts.add("extra: " + sorted(extra));
			}
			return path + " " + String.join(", ", parts);
		}

		private static List<String> sorted(Set<?> s) {
			List<String> list = new ArrayList<>(
					s.stream().map(Object::toString).toList()
			);
			list.sort(Comparator.naturalOrder());
			return list;
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new SetDrift(newPath, missing, extra);
		}

	}

	record SectionMissing(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": missing";
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new SectionMissing(newPath);
		}

	}

	record SectionExtra(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": extra (should not exist)";
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new SectionExtra(newPath);
		}

	}

	record SecretMissingBaseline(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path
					+ ": exists but has no recorded baseline (--fix pushes the configured value)";
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new SecretMissingBaseline(newPath);
		}

	}

	record SecretChanged(
			String path,
			String recordedUpdatedAt,
			String actualUpdatedAt
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": changed outside drifty (recorded "
					+ recordedUpdatedAt + ", now " + actualUpdatedAt + ")";
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new SecretChanged(
					newPath,
					recordedUpdatedAt,
					actualUpdatedAt
			);
		}

	}

	record SecretValueChanged(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": config value changed since last push";
		}

		@Override
		public DriftItem withPath(String newPath) {
			return new SecretValueChanged(newPath);
		}

	}

}
