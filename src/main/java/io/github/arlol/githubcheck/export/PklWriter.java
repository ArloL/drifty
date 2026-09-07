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

	private static final int WIDTH = 80;
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
		case PklNode.Scalar scalar -> out.append(indent(depth))
				.append(name)
				.append(" = ")
				.append(
						scalar.quoted() ? quote(scalar.value()) : scalar.value()
				)
				.append('\n');
		case PklNode.Note note -> throw new IllegalArgumentException(
				"a Field's value is never a Note — " + field.name()
						+ " would lose its name; add the Note as a sibling "
						+ "Member instead"
		);
		case PklNode.Obj obj -> {
			out.append(indent(depth)).append(name).append(" {\n");
			writeMembers(out, obj.members(), depth + 1);
			out.append(indent(depth)).append("}\n");
		}
		case PklNode.Mapping mapping -> {
			out.append(indent(depth)).append(name).append(" {\n");
			writeMapping(out, mapping, depth + 1);
			out.append(indent(depth)).append("}\n");
		}
		case PklNode.Listing listing -> {
			out.append(indent(depth))
					.append(name)
					.append(listing.replace() ? " = new Listing {\n" : " {\n");
			writeListing(out, listing, depth + 1);
			out.append(indent(depth)).append("}\n");
		}
		}
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
				out.append(indent(depth)).append("new {\n");
				writeMembers(out, obj.members(), depth + 1);
				out.append(indent(depth)).append("}\n");
			}
			case PklNode.Mapping mapping -> {
				out.append(indent(depth)).append("new {\n");
				writeMapping(out, mapping, depth + 1);
				out.append(indent(depth)).append("}\n");
			}
			case PklNode.Listing nested -> {
				out.append(indent(depth)).append("new {\n");
				writeListing(out, nested, depth + 1);
				out.append(indent(depth)).append("}\n");
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
		for (String line : wrap(note.text(), WIDTH - prefix.length())) {
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
