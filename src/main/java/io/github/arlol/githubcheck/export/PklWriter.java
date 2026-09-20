package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Renders a {@link PklNode} tree as Pkl source.
 * <p>
 * The only place in the export that knows Pkl syntax. A {@code Mapping} entry
 * is keyed ({@code ["main"] { … }}) and a {@code Listing} element is not
 * ({@code new { … }}); getting that backwards is a file that does not evaluate,
 * so the distinction lives in the node type rather than in each of the dozen
 * exporters.
 */
public final class PklWriter {

	/**
	 * What {@code pkl format} does to a line longer than this: it moves a
	 * scalar assignment's value onto its own line. An exported file is the
	 * first thing a new adopter commits, and drifty-arlol's CI runs
	 * {@code pkl format --diff-name-only} over it, so the writer produces the
	 * formatter's output rather than something it would rewrite (issue #138).
	 * Nothing else in the exported syntax is width-sensitive — a block header,
	 * a listing element and a comment are all left where they are however long
	 * they get.
	 */
	private static final int LINE_WIDTH = 100;

	/**
	 * Notes are wrapped narrower than {@link #LINE_WIDTH} because that is a
	 * readability choice, not the formatter's: {@code pkl format} never rewraps
	 * a comment.
	 */
	private static final int NOTE_WIDTH = 80;

	private static final String INDENT = "  ";

	private PklWriter() {
	}

	public static String write(PklNode node) {
		var out = new StringBuilder();
		writeMembers(out, membersOf(node), 0);
		return out.toString();
	}

	private static List<PklNode.Member> membersOf(PklNode node) {
		return switch (node) {
		case PklNode.Obj obj -> obj.members();
		case PklNode.Mapping mapping -> mapping.entries();
		default -> throw new IllegalArgumentException(
				"the root of a file is an Obj or a Mapping, not " + node
		);
		};
	}

	private static void writeMembers(
			StringBuilder out,
			List<PklNode.Member> members,
			int depth
	) {
		for (PklNode.Member member : members) {
			switch (member) {
			case PklNode.Note note -> writeNote(out, note, depth);
			case PklNode.Field field -> writeField(out, field, depth, false);
			}
		}
	}

	private static void writeField(
			StringBuilder out,
			PklNode.Field field,
			int depth,
			boolean keyed
	) {
		String name = keyed ? "[" + quote(field.name()) + "]" : field.name();
		switch (field.value()) {
		case PklNode.Scalar scalar -> writeAssignment(out, name, scalar, depth);
		case PklNode.Note note -> throw new IllegalArgumentException(
				"a Field's value is never a Note — " + field.name()
						+ " would lose its name; add the Note as a sibling "
						+ "Member instead"
		);
		case PklNode.Obj obj -> {
			out.append(indent(depth)).append(name);
			writeBlock(
					out,
					obj.members(),
					depth,
					body -> writeMembers(out, obj.members(), body)
			);
		}
		case PklNode.Mapping mapping -> {
			out.append(indent(depth)).append(name);
			writeBlock(
					out,
					mapping.entries(),
					depth,
					body -> writeMapping(out, mapping, body)
			);
		}
		case PklNode.Listing listing -> {
			out.append(indent(depth)).append(name);
			if (listing.replace()) {
				out.append(" = new Listing");
			}
			writeBlock(
					out,
					listing.elements(),
					depth,
					body -> writeListing(out, listing, body)
			);
		}
		}
	}

	/**
	 * A scalar assignment, broken the way {@code pkl format} breaks one: over
	 * {@link #LINE_WIDTH} columns the value moves to its own line, indented one
	 * level past the name. A value that is still too long there stays too long
	 * — the formatter does not split a string literal, and neither does this.
	 * <p>
	 * Measured over the escaped literal, in UTF-16 units: that is the source
	 * text the formatter sees, and it counts an emoji in a description as the
	 * two units {@code String.length()} does, not as one character.
	 */
	private static void writeAssignment(
			StringBuilder out,
			String name,
			PklNode.Scalar scalar,
			int depth
	) {
		String value = scalar.quoted() ? quote(scalar.value()) : scalar.value();
		String head = indent(depth) + name + " =";
		if (head.length() + 1 + value.length() > LINE_WIDTH) {
			out.append(head)
					.append('\n')
					.append(indent(depth + 1))
					.append(value)
					.append('\n');
			return;
		}
		out.append(head).append(' ').append(value).append('\n');
	}

	/**
	 * The braces around a body, opened after whatever the caller has already
	 * written on the line — a field name, {@code new}, {@code = new Listing}.
	 * An empty body is {@code {}} on that same line, because that is what
	 * {@code pkl format} collapses one to.
	 * <p>
	 * A body holding nothing but comments is the third shape, and it is the
	 * formatter's and not a choice: {@code pkl format} indents a comment to the
	 * member it precedes, and with no member to precede it falls back to the
	 * enclosing level, so it writes
	 *
	 * <pre>
	 *
	 * security {
	 * // vulnerability_alerts: HTTP 403
	 * }
	 * </pre>
	 *
	 * where every instinct says two more spaces. Reproducing it is the same
	 * bargain as the other two shapes — the writer's job is the formatter's
	 * output, not the prettier of the two — and the alternative is a rule
	 * saying no exporter may leave a block holding only notes, spread across a
	 * dozen exporters with nothing to check it. A future pkl release that
	 * indents these properly fails {@code PklWriterAgreesWithTheFormatterTest}
	 * on the version bump, which is where finding out belongs.
	 */
	private static void writeBlock(
			StringBuilder out,
			List<?> items,
			int depth,
			IntConsumer body
	) {
		if (items.isEmpty()) {
			out.append(" {}\n");
			return;
		}
		out.append(" {\n");
		body.accept(onlyNotes(items) ? depth : depth + 1);
		out.append(indent(depth)).append("}\n");
	}

	private static boolean onlyNotes(List<?> items) {
		return items.stream().allMatch(PklNode.Note.class::isInstance);
	}

	private static void writeMapping(
			StringBuilder out,
			PklNode.Mapping mapping,
			int depth
	) {
		for (PklNode.Member entry : mapping.entries()) {
			switch (entry) {
			case PklNode.Note note -> writeNote(out, note, depth);
			case PklNode.Field field -> writeField(out, field, depth, true);
			}
		}
	}

	private static void writeListing(
			StringBuilder out,
			PklNode.Listing listing,
			int depth
	) {
		for (PklNode element : listing.elements()) {
			switch (element) {
			case PklNode.Scalar scalar -> out.append(indent(depth))
					.append(
							scalar.quoted() ? quote(scalar.value())
									: scalar.value()
					)
					.append('\n');
			case PklNode.Note note -> writeNote(out, note, depth);
			case PklNode.Obj obj -> {
				out.append(indent(depth)).append("new");
				writeBlock(
						out,
						obj.members(),
						depth,
						body -> writeMembers(out, obj.members(), body)
				);
			}
			case PklNode.Mapping mapping -> {
				out.append(indent(depth)).append("new");
				writeBlock(
						out,
						mapping.entries(),
						depth,
						body -> writeMapping(out, mapping, body)
				);
			}
			case PklNode.Listing nested -> {
				out.append(indent(depth)).append("new");
				writeBlock(
						out,
						nested.elements(),
						depth,
						body -> writeListing(out, nested, body)
				);
			}
			}
		}
	}

	/**
	 * A note, wrapped so a long reason does not run off the side of a file a
	 * developer is reading.
	 */
	private static void writeNote(
			StringBuilder out,
			PklNode.Note note,
			int depth
	) {
		String prefix = indent(depth) + "// ";
		for (String line : wrap(note.text(), NOTE_WIDTH - prefix.length())) {
			out.append(prefix).append(line).append('\n');
		}
	}

	/**
	 * Splits on any run of whitespace, not on a single space, because a
	 * {@code //} comment ends at the first line break: a note carrying one
	 * would put everything after it into the file as bare Pkl. Most of what a
	 * note says is drifty's own prose, but the reason in a failure note comes
	 * from an exception message, which is somebody else's text.
	 * {@code FetchFailures.firstLine} takes the first line of one today, and
	 * that is a guard a layer away from the syntax it protects — this is the
	 * layer that owns the syntax, so it does not depend on the other one still
	 * being there.
	 */
	private static List<String> wrap(String text, int width) {
		var lines = new ArrayList<String>();
		var line = new StringBuilder();
		for (String word : text.strip().split("\\s+")) {
			if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(word);
		}
		lines.add(line.toString());
		return lines;
	}

	/**
	 * Package-visible so {@code DriftyFileExporter} can quote the
	 * {@code amends} URI with it: that line is hand-assembled outside the node
	 * tree, but it is still Pkl source, and a Windows path's backslashes are
	 * not valid escape sequences unless they go through this same quoting.
	 */
	static String quote(String value) {
		var out = new StringBuilder("\"");
		for (char c : value.toCharArray()) {
			switch (c) {
			case '"' -> out.append("\\\"");
			case '\\' -> out.append("\\\\");
			case '\n' -> out.append("\\n");
			case '\r' -> out.append("\\r");
			case '\t' -> out.append("\\t");
			default -> out.append(c);
			}
		}
		return out.append('"').toString();
	}

	private static String indent(int depth) {
		return INDENT.repeat(depth);
	}

}
