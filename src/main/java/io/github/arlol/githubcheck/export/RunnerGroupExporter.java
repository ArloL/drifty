package io.github.arlol.githubcheck.export;

import java.util.List;

import io.github.arlol.githubcheck.actual.ActualRunnerGroup;
import io.github.arlol.githubcheck.pkl.Drifty;

/**
 * A self-hosted runner group, as the config lines that differ from the schema's
 * defaults.
 * <p>
 * {@code isDefault} names no schema field: {@code OrgRunnerGroupsDriftGroup}
 * only uses it to decide whether an <em>unlisted</em> group is reported as
 * extra, and a group named in the config — default or not — is compared and
 * updated like any other. Exporting the default group's name the same way as
 * any other is therefore not the fix-it-cannot-perform case the schema's shape
 * would otherwise warn about.
 */
public final class RunnerGroupExporter {

	private RunnerGroupExporter() {
	}

	public static PklNode.Member entry(
			ActualRunnerGroup actual,
			Drifty.RunnerGroup defaults
	) {
		List<PklNode.Member> members = Fields.members(
				Fields.field(
						"visibility",
						actual.visibility(),
						defaults.visibility.toString()
				),
				Fields.strings(
						"selectedRepositories",
						actual.selectedRepositories(),
						defaults.selectedRepositories
				),
				Fields.field(
						"allowsPublicRepositories",
						actual.allowsPublicRepositories(),
						defaults.allowsPublicRepositories
				),
				Fields.field(
						"restrictedToWorkflows",
						actual.restrictedToWorkflows(),
						defaults.restrictedToWorkflows
				),
				Fields.strings(
						"selectedWorkflows",
						actual.selectedWorkflows(),
						defaults.selectedWorkflows
				)
		);
		return new PklNode.Field(actual.name(), new PklNode.Obj(members));
	}

}
