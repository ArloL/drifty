package io.github.arlol.githubcheck.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pkl.formatter.Formatter;

/**
 * {@link PklWriter} writes what {@code pkl format} writes, for trees nobody
 * thought to write down.
 * <p>
 * {@code PklWriterTest} states the two shapes the writer models — an empty body
 * collapsed onto the line that opens it, and a scalar assignment past
 * {@code LINE_WIDTH} moved to its own line — one example at a time, and
 * {@code TrackedPklFilesAreFormattedTest} and {@code ExportRoundTripTest} hold
 * real files to the real formatter. What neither reaches is the input space:
 * every file either of them formats came out of an exporter, so every string in
 * it is a GitHub value somebody chose to put in a fixture. A description with a
 * quote in it, a note that lands exactly on the wrap boundary, an object nested
 * eight deep — each is a file an adopter could be handed, and none of them is
 * in a fixture.
 * <p>
 * So the trees are generated and the property is the formatter's own: format
 * what the writer wrote, and it has to come back identical. That covers parsing
 * too, since the formatter cannot format what it cannot parse, which is what
 * makes a quoting bug a failure here rather than an adopter's first run.
 * <p>
 * It found one. A {@link PklNode.Note} whose text contains a line break ends
 * its own {@code //} comment, and everything after the break lands in the file
 * as bare Pkl — no exception, a file that does not evaluate.
 * {@code FetchFailures.firstLine} happens to strip line breaks out of the one
 * reason that comes from somebody else's text, so nothing reachable produced
 * it, but that guard is a layer away from the syntax it protects. It is
 * {@code PklWriter.wrap} that owns the syntax, and it now splits on any run of
 * whitespace.
 * <p>
 * The seeds are fixed rather than random. A generative test that picks a new
 * seed every run reports a failure the next run cannot reproduce, and this one
 * runs in CI where nobody is watching; a fixed seed makes the suite a constant
 * and a new seed a commit. {@link #shrink} is what replaces a framework here:
 * the tree that fails is usually a hundred nodes, and what a reader needs is
 * the three that matter.
 */
class PklWriterAgreesWithTheFormatterTest {

	private static final int TREES_PER_SEED = 500;

	/**
	 * The characters a generated string is built from: ASCII prose, the four
	 * the quoting has to escape, a Pkl interpolation opener, and a character
	 * outside the BMP — which is two UTF-16 units, and {@code writeAssignment}
	 * measures its line width in those.
	 */
	private static final String ALPHABET = "abc XYZ019 -_./*?" + "\"\\\n\t"
			+ "\\(" + "é✓🎉";

	private static final String IDENTIFIER_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = { 1L, 2L, 3L, 20260920L, -1L })
	void everyTreeItWritesIsAlreadyWhatTheFormatterWouldWrite(long seed) {
		var random = new Random(seed);
		for (int i = 0; i < TREES_PER_SEED; i++) {
			PklNode tree = root(random);
			if (disagrees(tree)) {
				report(shrink(tree), seed, i);
			}
		}
	}

	/**
	 * True when the writer's output is not a fixed point of the formatter,
	 * including when the formatter cannot read it at all — a tree the writer
	 * renders as something unparseable is the failure this is looking for, not
	 * an error in the test.
	 */
	private static boolean disagrees(PklNode tree) {
		String written;
		try {
			written = PklWriter.write(tree);
		} catch (RuntimeException e) {
			return true;
		}
		if (written.isEmpty()) {
			// The documented exception, and the shrinker has to know about it
			// too: every tree reduces to an empty root eventually, so a
			// predicate that called that a failure would shrink every real
			// finding into it and report the same uninformative tree each
			// time. See theOneShapeTheTwoDisagreeOn.
			return false;
		}
		try {
			return !written.equals(new Formatter().format(written));
		} catch (RuntimeException e) {
			return true;
		}
	}

	private static void report(PklNode tree, long seed, int index) {
		String written;
		try {
			written = PklWriter.write(tree);
		} catch (RuntimeException e) {
			fail(
					"PklWriter threw on tree %d of seed %d: %s%n%s",
					index,
					seed,
					e,
					tree
			);
			return;
		}
		String formatted;
		try {
			formatted = new Formatter().format(written);
		} catch (RuntimeException e) {
			fail(
					"`pkl format` cannot read what PklWriter wrote for tree %d"
							+ " of seed %d: %s%n[tree] %s%n[written]%n%s",
					index,
					seed,
					e,
					tree,
					written
			);
			return;
		}
		assertThat(written)
				.as("tree %d of seed %d, shrunk to %s", index, seed, tree)
				.isEqualTo(formatted);
	}

	/**
	 * The one tree the writer and the formatter render differently, and the
	 * reason the generator does not produce it: a root with no members.
	 * <p>
	 * The writer answers nothing, since it writes a fragment and an empty
	 * fragment is empty; the formatter answers a newline, because it is
	 * formatting a module and gives one a final line ending. Neither is wrong
	 * and no exported file is either — {@code DriftyFileExporter} writes three
	 * header lines and an {@code organizations} field before
	 * {@link PklWriter#write} sees the root, so the body it renders always has
	 * at least that one member.
	 * <p>
	 * Stated here rather than left out of the generator silently. It is
	 * asserted from both sides so that a change in either — the writer starting
	 * to emit a trailing newline, the formatter stopping — shows up as this
	 * test failing rather than as the exclusion quietly covering something new.
	 */
	@Test
	void theOneShapeTheTwoDisagreeOn() {
		String written = PklWriter.write(new PklNode.Obj(List.of()));
		assertThat(written).isEmpty();
		assertThat(new Formatter().format(written)).isEqualTo("\n");
	}

	// ─── Shrinking
	// ──────────────────────────────────────────────────────────

	/**
	 * The smallest still-failing tree reachable by repeatedly dropping one
	 * member, element or character.
	 * <p>
	 * Not a general shrinker: it only ever removes, so it terminates, and a
	 * reduction that stops the failure is simply not taken. That is enough for
	 * what a reader needs here — which node and which character — and it is
	 * about thirty lines against a test dependency.
	 */
	private static PklNode shrink(PklNode tree) {
		PklNode current = tree;
		boolean progress = true;
		while (progress) {
			progress = false;
			for (PklNode candidate : reductions(current)) {
				if (disagrees(candidate)) {
					current = candidate;
					progress = true;
					break;
				}
			}
		}
		return current;
	}

	/**
	 * Every one-step simplification of {@code node}: each member dropped in
	 * turn, each member replaced by its own reductions, and each string with
	 * its second half cut.
	 */
	private static List<PklNode> reductions(PklNode node) {
		var out = new ArrayList<PklNode>();
		switch (node) {
		case PklNode.Obj obj -> members(obj.members(), out, PklNode.Obj::new);
		case PklNode.Mapping mapping ->
			members(mapping.entries(), out, PklNode.Mapping::new);
		case PklNode.Listing listing -> {
			List<PklNode> elements = listing.elements();
			for (int i = 0; i < elements.size(); i++) {
				out.add(
						new PklNode.Listing(
								without(elements, i),
								listing.replace()
						)
				);
				int index = i;
				reductions(elements.get(i)).forEach(
						reduced -> out.add(
								new PklNode.Listing(
										replace(elements, index, reduced),
										listing.replace()
								)
						)
				);
			}
			if (listing.replace()) {
				out.add(new PklNode.Listing(elements, false));
			}
		}
		case PklNode.Scalar scalar -> halve(scalar.value())
				.map(shorter -> new PklNode.Scalar(shorter, scalar.quoted()))
				.ifPresent(out::add);
		case PklNode.Note note ->
			halve(note.text()).map(PklNode.Note::new).ifPresent(out::add);
		}
		return out;
	}

	private static void members(
			List<PklNode.Member> members,
			List<PklNode> out,
			Function<List<PklNode.Member>, PklNode> rebuild
	) {
		for (int i = 0; i < members.size(); i++) {
			out.add(rebuild.apply(withoutMember(members, i)));
			if (members.get(i) instanceof PklNode.Field field) {
				int index = i;
				reductions(field.value()).forEach(reduced -> {
					var replaced = new ArrayList<>(members);
					replaced.set(
							index,
							new PklNode.Field(field.name(), reduced)
					);
					out.add(rebuild.apply(replaced));
				});
			} else if (members.get(i) instanceof PklNode.Note note) {
				int index = i;
				reductions(note).forEach(reduced -> {
					var replaced = new ArrayList<>(members);
					replaced.set(index, (PklNode.Member) reduced);
					out.add(rebuild.apply(replaced));
				});
			}
		}
	}

	private static java.util.Optional<String> halve(String value) {
		return value.length() < 2 ? java.util.Optional.empty()
				: java.util.Optional.of(value.substring(0, value.length() / 2));
	}

	private static <T> List<T> without(List<T> values, int index) {
		var copy = new ArrayList<>(values);
		copy.remove(index);
		return copy;
	}

	private static List<PklNode.Member> withoutMember(
			List<PklNode.Member> members,
			int index
	) {
		return without(members, index);
	}

	private static <T> List<T> replace(List<T> values, int index, T value) {
		var copy = new ArrayList<>(values);
		copy.set(index, value);
		return copy;
	}

	// ─── Generation
	// ──────────────────────────────────────────────────────────

	/** A file's root, which {@link PklWriter} requires to be keyed or not. */
	private static PklNode root(Random random) {
		List<PklNode.Member> members = members(random, 0);
		return random.nextBoolean() ? new PklNode.Obj(members)
				: new PklNode.Mapping(members);
	}

	private static List<PklNode.Member> members(Random random, int depth) {
		// A root is never empty — see theOneShapeTheTwoDisagreeOn.
		int count = depth == 0 ? 1 + random.nextInt(3) : random.nextInt(3);
		var members = new ArrayList<PklNode.Member>();
		for (int i = 0; i < count; i++) {
			members.add(
					random.nextInt(6) == 0 ? new PklNode.Note(text(random))
							: new PklNode.Field(
									identifier(random),
									value(random, depth + 1)
							)
			);
		}
		return members;
	}

	/**
	 * Depth is capped at four. The writer's recursion is uniform below the
	 * first level, so a deeper tree is more of the same nesting and only makes
	 * a failure harder to read; what varies with depth is the indent, and four
	 * levels of it against a 100-column rule is already the interesting range.
	 */
	private static PklNode value(Random random, int depth) {
		int choice = depth >= 4 ? random.nextInt(2) : random.nextInt(5);
		return switch (choice) {
		case 0 -> scalar(random);
		case 1 -> new PklNode.Obj(List.of());
		case 2 -> new PklNode.Obj(members(random, depth));
		case 3 -> new PklNode.Mapping(members(random, depth));
		default ->
			new PklNode.Listing(elements(random, depth), random.nextBoolean());
		};
	}

	private static List<PklNode> elements(Random random, int depth) {
		int count = random.nextInt(3);
		var elements = new ArrayList<PklNode>();
		for (int i = 0; i < count; i++) {
			elements.add(
					random.nextInt(4) == 0
							? new PklNode.Obj(members(random, depth))
							: scalar(random)
			);
		}
		return elements;
	}

	/**
	 * A quoted string most of the time, and otherwise one of the three unquoted
	 * literals the writer emits — a bare {@code true}, {@code null} or number
	 * is a different path through {@code writeAssignment} than a quoted one,
	 * because nothing escapes it.
	 */
	private static PklNode.Scalar scalar(Random random) {
		return switch (random.nextInt(6)) {
		case 0 -> PklNode.Scalar.of(random.nextBoolean());
		case 1 -> PklNode.Scalar.nullValue();
		case 2 -> PklNode.Scalar.of(random.nextLong());
		default -> PklNode.Scalar.of(text(random));
		};
	}

	/**
	 * Lengths are drawn around the two widths the writer knows about, so a
	 * value landing exactly on {@code LINE_WIDTH} or a note landing exactly on
	 * {@code NOTE_WIDTH} is a case the generator produces rather than one it
	 * would have to be lucky to hit.
	 */
	private static String text(Random random) {
		int length = switch (random.nextInt(4)) {
		case 0 -> random.nextInt(8);
		case 1 -> 74 + random.nextInt(13);
		case 2 -> 94 + random.nextInt(13);
		default -> random.nextInt(140);
		};
		var out = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
		}
		return out.toString();
	}

	/** A Pkl identifier, which is what every exporter's field names are. */
	private static String identifier(Random random) {
		int length = 1 + random.nextInt(random.nextInt(8) == 0 ? 40 : 12);
		var out = new StringBuilder(length);
		out.append(IDENTIFIER_ALPHABET.charAt(random.nextInt(52)));
		for (int i = 1; i < length; i++) {
			out.append(
					IDENTIFIER_ALPHABET.charAt(
							random.nextInt(IDENTIFIER_ALPHABET.length())
					)
			);
		}
		return out.toString();
	}

}
