package io.github.arlol.githubcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.arlol.githubcheck.CheckResult.Entry;

class ReportTest {

	/**
	 * One counter per status the organizations section can print. With only
	 * "checked" and "drifted", an organization GitHub does not have summarised
	 * as {@code Orgs checked: 1 / Orgs drifted: 0} while the section above it
	 * said {@code [MISSING]} and the run exited 1.
	 */
	@Test
	void summaryCountsEveryOrgStatusTheDetailSectionPrints() {
		var result = new CheckResult(
				List.of(
						Entry.ok("ok-org"),
						Entry.drift(
								"drifted-org",
								List.of("org_settings.description: want=a got=")
						),
						Entry.error("broken-org", "HTTP 500"),
						Entry.missing("gone-org")
				),
				List.of()
		);

		String output = capture(result);

		assertThat(output).contains(
				"Orgs checked:   4",
				"Orgs OK:        1",
				"Orgs drifted:   1",
				"Orgs errored:   1",
				"Orgs missing:   1"
		);
	}

	@Test
	void summaryLeavesOutTheOrgCountersWhenNoOrganizationWasChecked() {
		String output = capture(
				CheckResult.ofRepos(List.of(Entry.ok("repo-a")))
		);

		assertThat(output).doesNotContain("Orgs").contains("Repos checked:  1");
	}

	private static String capture(CheckResult result) {
		PrintStream original = System.out;
		var captured = new ByteArrayOutputStream();
		try (var out = new PrintStream(
				captured,
				true,
				StandardCharsets.UTF_8
		)) {
			System.setOut(out);
			Report.print(result);
		} finally {
			System.setOut(original);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

}
