package io.github.arlol.githubcheck.export;

import java.util.ArrayList;
import java.util.List;

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
					obj.members().isEmpty(),
					depth,
					() -> writeMembers(out, obj.members(), depth + 1)
			);
		}
		case PklNode.Mapping mapping -> {
			out.append(indent(depth)).append(name);
			writeBlock(
					out,
					mapping.entries().isEmpty(),
					depth,
					() -> writeMapping(out, mapping, depth + 1)
			);
		}
		case PklNode.Listing listing -> {
			out.append(indent(depth)).append(name);
			if (listing.replace()) {
				out.append(" = new Listing");
			}
			writeBlock(
					out,
					listing.elements().isEmpty(),
					depth,
					() -> writeListing(out, listing, depth + 1)
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
	 */
	private static void writeBlock(
			StringBuilder out,
			boolean empty,
			int depth,
			Runnable body
	) {
		if (empty) {
			out.append(" {}\n");
			return;
		}
		out.append(" {\n");
		body.run();
		out.append(indent(depth)).append("}\n");
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
						obj.members().isEmpty(),
						depth,
						() -> writeMembers(out, obj.members(), depth + 1)
				);
			}
			case PklNode.Mapping mapping -> {
				out.append(indent(depth)).append("new");
				writeBlock(
						out,
						mapping.entries().isEmpty(),
						depth,
						() -> writeMapping(out, mapping, depth + 1)
				);
			}
			case PklNode.Listing nested -> {
				out.append(indent(depth)).append("new");
				writeBlock(
						out,
						nested.elements().isEmpty(),
						depth,
						() -> writeListing(out, nested, depth + 1)
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

	private static List<String> wrap(String text, int width) {
		var lines = new ArrayList<String>();
		var line = new StringBuilder();
		for (String word : text.split(" ")) {
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
