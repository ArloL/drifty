package io.github.arlol.githubcheck.export;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assembles the exported file around the two blocks {@link AccountExporter}
 * builds one entry of at a time.
 * <p>
 * The header names every login the run reached rather than taking a separate
 * list: {@code organizations} and {@code users} already carry one field per
 * account, keyed by login, so reading their names back is what the file itself
 * says was exported — a login that failed before it ever became an entry is not
 * part of that claim, which is the header's job, not a discrepancy.
 */
public final class DriftyFileExporter {

	private DriftyFileExporter() {
	}

	public static String file(
			String schemaUri,
			String version,
			Instant now,
			List<PklNode.Member> organizations,
			List<PklNode.Member> users
	) {
		var out = new StringBuilder();
		out.append("/// Exported by drifty ")
				.append(version)
				.append(" from ")
				.append(logins(organizations, users))
				.append(" on ")
				.append(now)
				.append(".\n");
		out.append(
				"/// Only settings that differ from the schema defaults are listed; everything\n"
		);
		out.append("/// absent is at GitHub's default.\n");
		out.append("amends \"").append(schemaUri).append("\"\n");
		out.append('\n');
		out.append(
				PklWriter.write(new PklNode.Obj(file(organizations, users)))
		);
		return out.toString();
	}

	private static List<PklNode.Member> file(
			List<PklNode.Member> organizations,
			List<PklNode.Member> users
	) {
		var members = new ArrayList<PklNode.Member>();
		members.add(
				new PklNode.Field(
						"organizations",
						new PklNode.Mapping(organizations)
				)
		);
		if (!users.isEmpty()) {
			members.add(new PklNode.Field("users", new PklNode.Mapping(users)));
		}
		return members;
	}

	private static String logins(
			List<PklNode.Member> organizations,
			List<PklNode.Member> users
	) {
		return Stream.concat(organizations.stream(), users.stream())
				.map(DriftyFileExporter::login)
				.collect(Collectors.joining(", "));
	}

	private static String login(PklNode.Member entry) {
		return switch (entry) {
		case PklNode.Field field -> field.name();
		// Every account entry AccountExporter hands back is a keyed field;
		// a Note here would mean a caller passed the wrong list.
		case PklNode.Note note -> throw new IllegalArgumentException(
				"an account entry must be a field, not a note: " + note.text()
		);
		};
	}

}
