package io.github.arlol.githubcheck.drift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public sealed interface DriftItem {

	String path();

	String message();

	record FieldMismatch(
			String path,
			Object wanted,
			Object got
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": want=" + wanted + " got=" + got;
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
			Collections.sort(list);
			return list;
		}

	}

	record SectionMissing(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": missing";
		}

	}

	record SectionExtra(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": extra (should not exist)";
		}

	}

	record SecretUnverifiable(
			String path
	) implements DriftItem {

		@Override
		public String message() {
			return path + ": unverifiable";
		}

	}

}
