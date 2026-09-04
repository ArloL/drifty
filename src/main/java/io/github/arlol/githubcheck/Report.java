package io.github.arlol.githubcheck;

import java.util.List;

public final class Report {

	private Report() {
	}

	public static void print(CheckResult result) {
		printEntries(
				result.repos(),
				"not in desired config",
				"in config but not found in org"
		);
		printSummary(result);
	}

	private static void printEntries(
			List<CheckResult.Entry> entries,
			String unknownSuffix,
			String missingSuffix
	) {
		List<CheckResult.Entry> sorted = entries.stream()
				.sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
				.toList();

		for (CheckResult.Entry r : sorted) {
			switch (r.status()) {
			case OK -> {
				System.out.printf("[OK]      %s%n", r.name());
				printUnmanaged(r);
				printFixReports(r);
			}
			case DRIFT -> {
				System.out.printf("[DRIFT]   %s:%n", r.name());
				if (r.fixReports().isEmpty()) {
					r.diffs()
							.forEach(
									d -> System.out
											.printf("            %s%n", d)
							);
				} else {
					printFixReports(r);
				}
				if (!r.fixPreview().isEmpty()) {
					System.out.printf(
							"  Would fix: %s%n",
							String.join(", ", r.fixPreview())
					);
				}
				printUnmanaged(r);
			}
			case ERROR ->
				System.out.printf("[ERROR]   %s: %s%n", r.name(), r.error());
			case UNKNOWN -> System.out
					.printf("[UNKNOWN] %s: %s%n", r.name(), unknownSuffix);
			case MISSING -> System.out
					.printf("[MISSING] %s: %s%n", r.name(), missingSuffix);
			}
		}
	}

	private static void printSummary(CheckResult result) {
		System.out.println();
		System.out.println("=== Summary ===");
		System.out.printf("Repos checked:  %d%n", result.repos().size());
		System.out.printf("OK:             %d%n", result.okCount());
		System.out.printf("Drifted:        %d%n", result.driftCount());
		System.out.printf("Errored:        %d%n", result.errorCount());
		System.out.printf("Unknown:        %d%n", result.unknownCount());
		System.out.printf("Missing:        %d%n", result.missingCount());

		List<String> failures = result.fixFailures();
		if (!failures.isEmpty()) {
			System.out.println();
			System.out.printf("=== Failed fixes (%d) ===%n", failures.size());
			failures.forEach(f -> System.out.printf("  %s%n", f));
		}
	}

	/**
	 * Names the groups, not their values: an unmanaged group's actual values
	 * were never fetched, and fetching them to print would undo the point of
	 * declaring it unmanaged.
	 */
	private static void printUnmanaged(CheckResult.Entry r) {
		if (!r.unmanaged().isEmpty()) {
			System.out.printf(
					"  Unmanaged: %s%n",
					String.join(", ", r.unmanaged())
			);
		}
	}

	private static void printFixReports(CheckResult.Entry r) {
		r.fixReports()
				.forEach(
						report -> System.out
								.printf("            %s%n", report.message())
				);
	}

}
